package com.shaterguy.gptwebcapture;

import android.net.Uri;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class VisibleWebViewInteractionAndroidTest {
    private static final String FIXTURE = "<!doctype html><html><head>" +
            "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<title>ready</title></head><body style='margin:0'>" +
            "<button id='tap' style='position:fixed;left:0;top:0;width:100%;height:45%;font-size:32px' " +
            "onclick='window.__clicked=true;document.title=\"clicked\"'>tap</button>" +
            "<main style='position:fixed;left:0;right:0;top:50%;bottom:0'>" +
            "<form id='composer-form'><div id='project-editor' role='textbox' contenteditable='true' data-testid='project-composer'>hello</div>" +
            "<input type='password' value='password-value-should-never-export'>" +
            "<input type='hidden' name='session_token' value='hidden-token-should-never-export'></form></main>" +
            "</body></html>";

    @Test
    public void sameProductionWebViewRemainsTouchableCapturableAndLoginNavigable() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<WebView> webRef = new AtomicReference<>();
            scenario.onActivity(activity -> {
                assertTrue("passive document-start hook was not installed", activity.passiveHookInstalledForInstrumentationTest());
                CaptureWebViewClient loginClient = new CaptureWebViewClient(activity, new TrafficRecorder());
                assertFalse("auth.openai.com navigation was blocked",
                        loginClient.shouldOverrideUrlLoading(activity.webViewForInstrumentationTest(), request("https://auth.openai.com/authorize")));
                assertFalse("external HTTPS IdP navigation was blocked",
                        loginClient.shouldOverrideUrlLoading(activity.webViewForInstrumentationTest(), request("https://accounts.google.com/o/oauth2/v2/auth")));

                WebView web = activity.webViewForInstrumentationTest();
                webRef.set(web);
                web.loadDataWithBaseURL("https://chatgpt.com/", FIXTURE, "text/html", "UTF-8", null);
            });

            WebView web = webRef.get();
            assertNotNull(web);
            assertTrue("fixture did not become ready", waitForJs(web,
                    "document.readyState==='complete' && !!document.getElementById('tap')", "true", 10_000));
            assertEquals("true", eval(web, "window.__GPT_WEB_CAPTURE_HOOK__ && window.__GPT_WEB_CAPTURE_HOOK__.passiveOnly===true"));
            assertEquals("true", eval(web, "window.__GPT_WEB_CAPTURE_HOOK__.attachShadowPatched===false"));
            assertEquals("true", eval(web, "typeof window.GPTCaptureBridge==='object'"));

            scenario.onActivity(activity -> {
                WebView current = activity.webViewForInstrumentationTest();
                assertTrue("visible WebView has no width", current.getWidth() > 0);
                assertTrue("visible WebView has no height", current.getHeight() > 0);
                float x = current.getWidth() / 2f;
                float y = current.getHeight() * 0.2f;
                long down = SystemClock.uptimeMillis();
                MotionEvent downEvent = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
                MotionEvent upEvent = MotionEvent.obtain(down, down + 80, MotionEvent.ACTION_UP, x, y, 0);
                try {
                    current.dispatchTouchEvent(downEvent);
                    current.dispatchTouchEvent(upEvent);
                } finally {
                    downEvent.recycle();
                    upEvent.recycle();
                }
            });

            assertTrue("real touch did not activate the page button", waitForJs(web,
                    "window.__clicked===true && document.title==='clicked'", "true", 10_000));
            assertEquals("true", eval(web, "window.__GPT_WEB_CAPTURE_HOOK__.events.some(e=>e.type==='pointerdown'||e.type==='click')"));

            CountDownLatch captureDone = new CountDownLatch(1);
            AtomicReference<File> zipRef = new AtomicReference<>();
            AtomicReference<String> captureFailure = new AtomicReference<>();
            scenario.onActivity(activity -> activity.captureForInstrumentationTest(new DiagnosticRecorder.Callback() {
                @Override public void onSuccess(File zip) {
                    zipRef.set(zip);
                    captureDone.countDown();
                }
                @Override public void onFailure(String message) {
                    captureFailure.set(message);
                    captureDone.countDown();
                }
            }));

            assertTrue("production same-WebView capture did not finish", captureDone.await(60, TimeUnit.SECONDS));
            assertNull("production capture failed: " + captureFailure.get(), captureFailure.get());
            File zip = zipRef.get();
            assertNotNull("capture returned no ZIP", zip);
            assertTrue("capture ZIP missing", zip.isFile() && zip.length() > 0);

            try (ZipFile captureZip = new ZipFile(zip)) {
                assertNotNull(captureZip.getEntry("manifest.json"));
                assertNotNull(captureZip.getEntry("web/dom/sanitized.html"));
                assertNotNull(captureZip.getEntry("web/dom/composer-candidates.json"));
                assertNotNull(captureZip.getEntry("web/timeline/hook-status.json"));
                assertNotNull(captureZip.getEntry("network/events.json"));
                assertNotNull(captureZip.getEntry("console/events.json"));

                String dom = readEntry(captureZip, "web/dom/sanitized.html");
                assertFalse(dom.contains("password-value-should-never-export"));
                assertFalse(dom.contains("hidden-token-should-never-export"));

                JSONObject candidates = new JSONObject(readEntry(captureZip, "web/dom/composer-candidates.json"));
                JSONArray roleTextboxes = candidates.getJSONObject("selectors").getJSONArray("[role=\"textbox\"]");
                assertTrue("composer candidate missing from same WebView ZIP", roleTextboxes.length() > 0);
                assertEquals("project-composer", roleTextboxes.getJSONObject(0).getString("testId"));
            } finally {
                // The test owns this internal capture artifact.
                zip.delete();
            }
        }
    }

    private static WebResourceRequest request(String url) {
        return new WebResourceRequest() {
            @Override public Uri getUrl() { return Uri.parse(url); }
            @Override public boolean isForMainFrame() { return true; }
            @Override public boolean isRedirect() { return false; }
            @Override public boolean hasGesture() { return true; }
            @Override public String getMethod() { return "GET"; }
            @Override public Map<String, String> getRequestHeaders() { return Collections.emptyMap(); }
        };
    }

    private static boolean waitForJs(WebView web, String expression, String expected, long timeoutMs) throws Exception {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < deadline) {
            if (expected.equals(eval(web, expression))) return true;
            Thread.sleep(100);
        }
        return false;
    }

    private static String eval(WebView web, String expression) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        web.post(() -> web.evaluateJavascript(expression, new ValueCallback<String>() {
            @Override public void onReceiveValue(String value) {
                result.set(value);
                latch.countDown();
            }
        }));
        assertTrue("evaluateJavascript timeout", latch.await(5, TimeUnit.SECONDS));
        return result.get();
    }

    private static String readEntry(ZipFile zip, String name) throws Exception {
        ZipEntry entry = zip.getEntry(name);
        assertNotNull("missing ZIP entry: " + name, entry);
        try (InputStream in = zip.getInputStream(entry); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
