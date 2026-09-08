package com.shaterguy.gptwebcapture;

import android.annotation.SuppressLint;
import android.content.Context;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class CaptureScriptWebViewAndroidTest {
    private static final String BASE_URL = "https://example.test/";
    private static final String HTML = "<!doctype html><html><head><title>fixture</title></head><body>" +
            "<main><form id='composer-form'><div id='project-editor' role='textbox' contenteditable='true' data-testid='project-composer'>hello</div>" +
            "<input type='password' value='password-value-should-never-export'><input type='hidden' name='session_token' value='hidden-token-should-never-export'></form></main>" +
            "<div id='open-host'></div><div id='closed-host'></div>" +
            "<script>window.inlineSecret='inline-script-secret-should-never-export';" +
            "document.getElementById('open-host').attachShadow({mode:'open'}).innerHTML='<span id=inside-open>open</span>';" +
            "document.getElementById('closed-host').attachShadow({mode:'closed'}).innerHTML='<span id=inside-closed>closed</span>';" +
            "</script></body></html>";

    @SuppressLint("SetJavaScriptEnabled")
    @Test
    public void productionVirtualDisplayHostRunsCaptureAndRedactsSecrets() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AtomicReference<HeadlessWebViewHost> hostRef = new AtomicReference<>();
        CountDownLatch pageLoaded = new CountDownLatch(1);
        TestBridge bridge = new TestBridge();
        boolean documentStartSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT);

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            HeadlessWebViewHost host = HeadlessWebViewHost.create(context);
            hostRef.set(host);
            WebView web = host.webView();
            web.getSettings().setJavaScriptEnabled(true);
            web.getSettings().setDomStorageEnabled(true);
            web.addJavascriptInterface(bridge, "GPTCaptureBridge");
            web.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView view, String url) { pageLoaded.countDown(); }
            });
            try {
                if (documentStartSupported) {
                    String hook = readAsset(context, "hook.js");
                    WebViewCompat.addDocumentStartJavaScript(
                            web,
                            hook,
                            Collections.singleton(BASE_URL.substring(0, BASE_URL.length() - 1)));
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            web.loadDataWithBaseURL(BASE_URL, HTML, "text/html", "UTF-8", null);
        });

        HeadlessWebViewHost host = hostRef.get();
        assertNotNull(host);
        assertTrue("production capture host fell back instead of creating a VirtualDisplay", host.isVirtualDisplay());
        assertTrue("production VirtualDisplay WebView is not attached", host.isWindowAttached());
        assertEquals(1440, HeadlessWebViewHost.WIDTH);
        assertEquals(900, HeadlessWebViewHost.HEIGHT);
        assertEquals(160, HeadlessWebViewHost.DENSITY_DPI);

        assertTrue("fixture page did not load", pageLoaded.await(20, TimeUnit.SECONDS));
        String captureJs = readAsset(context, "capture.js");
        String token = "android-test-token";
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            WebView web = hostRef.get().webView();
            web.evaluateJavascript(captureJs, ignored -> web.evaluateJavascript(
                    "window.__GPT_WEB_CAPTURE__.run('GPTCaptureBridge'," + JSONObject.quote(token) + ")", ignored2 -> {}));
        });

        assertTrue("capture runtime did not finish", bridge.finished.await(45, TimeUnit.SECONDS));
        assertNull("capture runtime failed: " + bridge.failure.get(), bridge.failure.get());
        assertTrue(bridge.parts.containsKey("dom/sanitized.html"));
        assertTrue(bridge.parts.containsKey("dom/composer-candidates.json"));
        assertTrue(bridge.parts.containsKey("shadow/index.json"));
        assertTrue(bridge.parts.containsKey("timeline/hook-status.json"));

        String dom = bridge.parts.get("dom/sanitized.html").toString();
        assertFalse(dom.contains("password-value-should-never-export"));
        assertFalse(dom.contains("hidden-token-should-never-export"));
        assertFalse(dom.contains("inline-script-secret-should-never-export"));
        assertTrue(dom.contains("project-editor"));

        JSONObject candidates = new JSONObject(bridge.parts.get("dom/composer-candidates.json").toString());
        JSONArray roleTextboxes = candidates.getJSONObject("selectors").getJSONArray("[role=\"textbox\"]");
        assertTrue("role=textbox candidate missing", roleTextboxes.length() > 0);
        assertEquals("project-composer", roleTextboxes.getJSONObject(0).getString("testId"));

        JSONArray shadowIndex = new JSONArray(bridge.parts.get("shadow/index.json").toString());
        boolean hasOpen = false;
        boolean hasClosed = false;
        for (int i = 0; i < shadowIndex.length(); i++) {
            String mode = shadowIndex.getJSONObject(i).optString("mode");
            if ("open".equals(mode)) hasOpen = true;
            if ("closed".equals(mode)) hasClosed = true;
        }
        assertTrue("open shadow root missing", hasOpen);
        if (documentStartSupported) assertTrue("closed shadow root was not retained", hasClosed);

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            HeadlessWebViewHost current = hostRef.get();
            if (current != null) current.destroy();
        });
    }

    private static String readAsset(Context context, String name) {
        try (InputStream in = context.getAssets().open(name); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static final class TestBridge {
        final Map<String, StringBuilder> parts = new ConcurrentHashMap<>();
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicReference<String> failure = new AtomicReference<>();

        @JavascriptInterface
        public synchronized void push(String token, String path, int sequence, int total, String chunk) {
            parts.computeIfAbsent(path, ignored -> new StringBuilder()).append(chunk == null ? "" : chunk);
        }

        @JavascriptInterface
        public void complete(String token, String summary) {
            finished.countDown();
        }

        @JavascriptInterface
        public void fail(String token, String error) {
            failure.set(error);
            finished.countDown();
        }
    }
}
