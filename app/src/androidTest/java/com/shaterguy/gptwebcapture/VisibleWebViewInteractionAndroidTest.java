package com.shaterguy.gptwebcapture;

import android.os.SystemClock;
import android.view.MotionEvent;
import android.webkit.ValueCallback;
import android.webkit.WebView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class VisibleWebViewInteractionAndroidTest {
    private static final String FIXTURE = "<!doctype html><html><head>" +
            "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<title>ready</title></head><body style='margin:0'>" +
            "<button id='tap' style='position:fixed;inset:0;width:100%;height:100%;font-size:32px' " +
            "onclick='window.__clicked=true;document.title=\"clicked\"'>tap</button>" +
            "</body></html>";

    @Test
    public void productionVisibleWebViewReceivesRealTouchWithoutPersistentHook() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<WebView> webRef = new AtomicReference<>();
            scenario.onActivity(activity -> {
                WebView web = activity.webViewForInstrumentationTest();
                webRef.set(web);
                web.loadDataWithBaseURL("https://chatgpt.com/", FIXTURE, "text/html", "UTF-8", null);
            });

            WebView web = webRef.get();
            assertNotNull(web);
            assertTrue("fixture did not become ready", waitForJs(web,
                    "document.readyState==='complete' && !!document.getElementById('tap')", "true", 10_000));
            assertEquals("true", eval(web, "typeof window.__GPT_WEB_CAPTURE_HOOK__==='undefined'"));
            assertEquals("true", eval(web, "typeof window.__GPT_WEB_CAPTURE__==='undefined'"));

            scenario.onActivity(activity -> {
                WebView current = activity.webViewForInstrumentationTest();
                assertTrue("visible WebView has no width", current.getWidth() > 0);
                assertTrue("visible WebView has no height", current.getHeight() > 0);
                float x = current.getWidth() / 2f;
                float y = current.getHeight() / 2f;
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
        }
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
}
