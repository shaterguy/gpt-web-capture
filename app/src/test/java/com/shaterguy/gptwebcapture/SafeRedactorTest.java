package com.shaterguy.gptwebcapture;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class SafeRedactorTest {
    @Test
    public void sensitiveQueryValuesAreNotPersisted() {
        String secret = "session-super-secret-value-1234567890";
        String input = "https://chatgpt.com/g/g-p-0123456789abcdef0123456789abcdef/project?foo=bar&access_token=" + secret;
        String output = SafeRedactor.redactUrl(input);
        assertTrue(output.contains("/g/g-p-0123456789abcdef0123456789abcdef/project"));
        assertTrue(output.contains("foo=bar"));
        assertFalse(output.contains(secret));
        assertTrue(output.contains("REDACTED"));
    }

    @Test
    public void cookieAndAuthorizationHeadersExposeMetadataOnly() throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", "WebView Test");
        headers.put("Authorization", "Bearer top-secret-token-value");
        headers.put("Cookie", "session=super-secret-cookie");
        JSONObject output = SafeRedactor.redactHeaders(headers);
        assertEquals("WebView Test", output.getString("User-Agent"));
        assertFalse(output.getString("Authorization").contains("top-secret-token-value"));
        assertFalse(output.getString("Cookie").contains("super-secret-cookie"));
        assertTrue(output.getString("Authorization").contains("sha256="));
        assertTrue(output.getString("Cookie").contains("sha256="));
    }

    @Test
    public void cookieMetadataContainsNamesLengthAndHashButNoValues() throws Exception {
        String first = "alpha-secret";
        String second = "beta-secret";
        JSONArray output = SafeRedactor.cookieMetadata("session=" + first + "; preference=" + second);
        assertEquals(2, output.length());
        String serialized = output.toString();
        assertFalse(serialized.contains(first));
        assertFalse(serialized.contains(second));
        assertTrue(serialized.contains("session"));
        assertTrue(serialized.contains("preference"));
        assertTrue(serialized.contains(SafeRedactor.sha256(first)));
    }

    @Test
    public void genericJwtIsScrubbedFromConsoleText() {
        String jwt = "abcdefghijk.abcdefghijklmnop.qrstuvwxyz012345";
        String output = SafeRedactor.scrubText("failed token=" + jwt);
        assertFalse(output.contains(jwt));
        assertTrue(output.contains("REDACTED"));
    }
}
