package com.shaterguy.gptwebcapture;

import android.content.Context;
import android.graphics.Bitmap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class CapturePackage {
    private final Context context;
    private final File root;
    private final String captureId;
    private final String createdAt;
    private final String sourceUrl;
    private final JSONArray failures = new JSONArray();

    CapturePackage(Context context, String sourceUrl) throws IOException {
        this.context = context.getApplicationContext();
        this.captureId = UUID.randomUUID().toString();
        this.createdAt = Instant.now().toString();
        this.sourceUrl = sourceUrl == null ? "" : sourceUrl;
        this.root = new File(context.getCacheDir(), "gpt-web-capture-" + captureId);
        if (!root.mkdirs() && !root.isDirectory()) throw new IOException("capture directory creation failed");
    }

    String captureId() { return captureId; }

    synchronized File fileFor(String relativePath) throws IOException {
        String safe = validateRelativePath(relativePath);
        File target = new File(root, safe);
        File canonicalRoot = root.getCanonicalFile();
        File canonicalTarget = target.getCanonicalFile();
        String prefix = canonicalRoot.getPath() + File.separator;
        if (!canonicalTarget.getPath().startsWith(prefix)) throw new IOException("path escaped capture root");
        File parent = canonicalTarget.getParentFile();
        if (parent != null && !parent.mkdirs() && !parent.isDirectory()) throw new IOException("parent directory creation failed");
        return canonicalTarget;
    }

    synchronized void writeText(String relativePath, String content) throws IOException {
        File target = fileFor(relativePath);
        try (FileOutputStream out = new FileOutputStream(target, false)) {
            out.write((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
        }
    }

    synchronized void writeJson(String relativePath, JSONObject json) throws IOException {
        final String content;
        try {
            content = json == null ? "{}" : json.toString(2);
        } catch (Exception e) {
            throw new IOException("JSON serialization failed", e);
        }
        writeText(relativePath, content);
    }

    synchronized void appendTextChunk(String relativePath, String chunk, boolean truncate) throws IOException {
        File target = fileFor(relativePath);
        try (FileOutputStream out = new FileOutputStream(target, !truncate)) {
            out.write((chunk == null ? "" : chunk).getBytes(StandardCharsets.UTF_8));
        }
    }

    synchronized void writeBitmap(String relativePath, Bitmap bitmap) throws IOException {
        if (bitmap == null) throw new IOException("bitmap is null");
        File target = fileFor(relativePath);
        try (FileOutputStream out = new FileOutputStream(target, false)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("PNG compression failed");
        }
    }

    synchronized void recordFailure(String artifact, String error) {
        JSONObject item = new JSONObject();
        try {
            item.put("artifact", artifact == null ? "unknown" : artifact);
            item.put("error", SafeRedactor.scrubText(error));
            item.put("timestampMs", System.currentTimeMillis());
            failures.put(item);
        } catch (Exception ignored) {}
    }

    File buildZip(JSONObject captureSummary) throws IOException {
        JSONObject manifest = new JSONObject();
        JSONArray artifacts = new JSONArray();
        List<File> files = listFiles(root);
        for (File file : files) {
            if (file.getName().equals("manifest.json")) continue;
            JSONObject item = new JSONObject();
            try {
                item.put("path", relative(file));
                item.put("size", file.length());
                item.put("sha256", sha256File(file));
                artifacts.put(item);
            } catch (Exception e) {
                recordFailure(relative(file), "manifest hash failed: " + e);
            }
        }
        try {
            manifest.put("schemaVersion", 1);
            manifest.put("captureId", captureId);
            manifest.put("createdAt", createdAt);
            manifest.put("sourceUrl", SafeRedactor.redactUrl(sourceUrl));
            manifest.put("appVersion", BuildConfig.VERSION_NAME);
            manifest.put("applicationId", BuildConfig.APPLICATION_ID);
            manifest.put("buildType", BuildConfig.BUILD_TYPE);
            manifest.put("flavor", BuildConfig.FLAVOR);
            manifest.put("artifacts", artifacts);
            manifest.put("failures", failures);
            manifest.put("manifestExcludedFromOwnChecksum", true);
            manifest.put("captureSummary", captureSummary == null ? new JSONObject() : captureSummary);
            manifest.put("redactionPolicy", "Structured JSON/HTML/network/console outputs redact authentication secrets, cookie values, authorization headers, password/hidden form values, storage values, nonces and inline script bodies. page/webarchive.mht is a raw private WebView archive and does not receive the same structural redaction guarantee; treat the entire ZIP as sensitive diagnostic data.");
        } catch (Exception ignored) {}
        writeJson("manifest.json", manifest);

        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
                .withZone(ZoneOffset.UTC).format(Instant.now());
        File zip = new File(context.getCacheDir(), "gpt-web-capture-" + stamp + "-" + captureId.substring(0, 8) + ".zip");
        List<File> finalFiles = listFiles(root);
        try (ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zip)))) {
            byte[] buffer = new byte[64 * 1024];
            for (File file : finalFiles) {
                ZipEntry entry = new ZipEntry(relative(file));
                entry.setTime(file.lastModified());
                out.putNextEntry(entry);
                try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(file))) {
                    int read;
                    while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                }
                out.closeEntry();
            }
        }
        if (!zip.isFile() || zip.length() == 0) throw new IOException("zip creation failed");
        deleteRecursively(root);
        return zip;
    }

    private String relative(File file) throws IOException {
        String rootPath = root.getCanonicalPath();
        String filePath = file.getCanonicalPath();
        if (!filePath.startsWith(rootPath + File.separator)) throw new IOException("file escaped capture root");
        return filePath.substring(rootPath.length() + 1).replace(File.separatorChar, '/');
    }

    private static String validateRelativePath(String path) throws IOException {
        if (path == null || path.isEmpty() || path.startsWith("/") || path.startsWith("\\") || path.contains("..")) {
            throw new IOException("invalid relative path");
        }
        if (!path.matches("[A-Za-z0-9._/-]+")) throw new IOException("unsupported path characters");
        return path;
    }

    private static List<File> listFiles(File directory) {
        List<File> out = new ArrayList<>();
        File[] children = directory.listFiles();
        if (children == null) return out;
        for (File child : children) {
            if (child.isDirectory()) out.addAll(listFiles(child));
            else if (child.isFile()) out.add(child);
        }
        out.sort(Comparator.comparing(File::getPath));
        return out;
    }

    private static String sha256File(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(file))) {
                int read;
                while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            StringBuilder out = new StringBuilder();
            for (byte b : digest.digest()) out.append(String.format(Locale.ROOT, "%02x", b));
            return out.toString();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
