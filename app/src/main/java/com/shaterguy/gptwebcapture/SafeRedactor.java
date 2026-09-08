package com.shaterguy.gptwebcapture;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SafeRedactor {
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i)(token|auth|authorization|cookie|session|password|passwd|secret|api[-_]?key|credential|jwt|access|refresh|csrf|xsrf|code|signature|sig|nonce)");
    private static final Pattern JWT = Pattern.compile("(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}(?![A-Za-z0-9_-])");
    private static final Pattern BEARER = Pattern.compile("(?i)Bearer\\s+[^\\s,;]{8,}");
    private static final Pattern OPENAI_KEY = Pattern.compile("(?i)\\b(?:sk|sess|key)-[A-Za-z0-9_-]{16,}\\b");
    private static final Pattern LONG_TOKEN = Pattern.compile("(?<![A-Za-z0-9_=-])[A-Za-z0-9_+/=-]{64,}(?![A-Za-z0-9_=-])");

    private SafeRedactor() {}

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b));
            return out.toString();
        } catch (Exception e) {
            return "sha256-unavailable";
        }
    }

    static boolean isSensitiveName(String name) {
        return name != null && SENSITIVE_KEY.matcher(name).find();
    }

    static String secretMetadata(String value) {
        String safe = value == null ? "" : value;
        return "[REDACTED len=" + safe.length() + " sha256=" + sha256(safe) + "]";
    }

    static String scrubText(String input) {
        String value = input == null ? "" : input;
        value = replaceSecretMatches(value, BEARER);
        value = replaceSecretMatches(value, JWT);
        value = replaceSecretMatches(value, OPENAI_KEY);
        value = replaceSecretMatches(value, LONG_TOKEN);
        return value;
    }

    private static String replaceSecretMatches(String value, Pattern pattern) {
        Matcher matcher = pattern.matcher(value);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(secretMetadata(matcher.group())));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    static String redactUrl(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        try {
            URI uri = URI.create(raw);
            StringBuilder out = new StringBuilder();
            if (uri.getScheme() != null) out.append(uri.getScheme()).append("://");
            if (uri.getRawAuthority() != null) out.append(uri.getRawAuthority());
            if (uri.getRawPath() != null) out.append(uri.getRawPath());
            String query = uri.getRawQuery();
            if (query != null && !query.isEmpty()) {
                out.append('?');
                String[] pairs = query.split("&", -1);
                for (int i = 0; i < pairs.length; i++) {
                    if (i > 0) out.append('&');
                    String pair = pairs[i];
                    int eq = pair.indexOf('=');
                    String rawKey = eq >= 0 ? pair.substring(0, eq) : pair;
                    String rawValue = eq >= 0 ? pair.substring(eq + 1) : "";
                    String key = decode(rawKey);
                    String decodedValue = decode(rawValue);
                    out.append(rawKey);
                    if (eq >= 0) {
                        out.append('=');
                        if (isSensitiveName(key) || decodedValue.length() > 96 || looksLikeStructuredSecret(decodedValue)) {
                            out.append(encode(secretMetadata(decodedValue)));
                        } else {
                            out.append(encode(scrubText(decodedValue)));
                        }
                    }
                }
            }
            if (uri.getRawFragment() != null && !uri.getRawFragment().isEmpty()) {
                out.append('#').append(encode(secretMetadata(decode(uri.getRawFragment()))));
            }
            return out.toString();
        } catch (Exception ignored) {
            return scrubText(raw);
        }
    }

    private static boolean looksLikeStructuredSecret(String value) {
        if (value == null) return false;
        return JWT.matcher(value).find() || OPENAI_KEY.matcher(value).find();
    }

    private static String decode(String value) {
        try { return URLDecoder.decode(value, "UTF-8"); }
        catch (Exception e) { return value; }
    }

    private static String encode(String value) {
        try { return URLEncoder.encode(value, "UTF-8").replace("+", "%20"); }
        catch (Exception e) { return value; }
    }

    static JSONObject redactHeaders(Map<String, String> headers) {
        JSONObject out = new JSONObject();
        if (headers == null) return out;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String name = entry.getKey() == null ? "" : entry.getKey();
            String value = entry.getValue() == null ? "" : entry.getValue();
            try {
                out.put(name, isSensitiveName(name) ? secretMetadata(value) : scrubText(value));
            } catch (Exception ignored) {}
        }
        return out;
    }

    static JSONArray cookieMetadata(String cookieHeader) {
        JSONArray out = new JSONArray();
        if (cookieHeader == null || cookieHeader.isEmpty()) return out;
        for (String part : cookieHeader.split(";\\s*")) {
            int eq = part.indexOf('=');
            String name = eq >= 0 ? part.substring(0, eq).trim() : part.trim();
            String value = eq >= 0 ? part.substring(eq + 1) : "";
            if (name.isEmpty()) continue;
            JSONObject item = new JSONObject();
            try {
                item.put("name", name);
                item.put("valueLength", value.length());
                item.put("valueSha256", sha256(value));
                out.put(item);
            } catch (Exception ignored) {}
        }
        return out;
    }
}
