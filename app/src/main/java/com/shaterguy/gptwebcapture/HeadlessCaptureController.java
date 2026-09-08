package com.shaterguy.gptwebcapture;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.webkit.WebView;

import androidx.webkit.ScriptHandler;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

final class HeadlessCaptureController {
    interface Callback {
        void onSuccess(File zip);
        void onFailure(String message);
    }

    private static final long ATTACH_TIMEOUT_MS = 5_000L;
    private static final long ATTACH_POLL_MS = 50L;
    private static final long SETTLE_AFTER_FINISH_MS = 10_000L;
    private static final long FALLBACK_CAPTURE_MS = 30_000L;

    private final MainActivity activity;
    private final String targetUrl;
    private final Callback callback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean captureScheduled = new AtomicBoolean(false);
    private final AtomicBoolean finished = new AtomicBoolean(false);

    private HeadlessWebViewHost host;
    private TrafficRecorder traffic;
    private ScriptHandler hookHandler;
    private long attachDeadlineUptimeMs;

    HeadlessCaptureController(MainActivity activity, String targetUrl, Callback callback) {
        this.activity = activity;
        this.targetUrl = targetUrl;
        this.callback = callback;
    }

    void start() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(this::start);
            return;
        }
        try {
            host = HeadlessWebViewHost.create(activity);
            if (!host.isVirtualDisplay()) {
                fail("VirtualDisplay creation failed; background capture requires the automation-style host");
                return;
            }
            attachDeadlineUptimeMs = SystemClock.uptimeMillis() + ATTACH_TIMEOUT_MS;
            awaitAttachmentThenLoad();
        } catch (Exception e) {
            fail(e.toString());
        }
    }

    void cancel() {
        if (finished.compareAndSet(false, true)) cleanup();
    }

    private void awaitAttachmentThenLoad() {
        if (finished.get() || host == null) return;
        if (host.isWindowAttached()) {
            configureAndLoad();
            return;
        }
        if (SystemClock.uptimeMillis() >= attachDeadlineUptimeMs) {
            fail("VirtualDisplay WebView did not attach within " + ATTACH_TIMEOUT_MS + " ms");
            return;
        }
        handler.postDelayed(this::awaitAttachmentThenLoad, ATTACH_POLL_MS);
    }

    private void configureAndLoad() {
        if (finished.get() || host == null) return;
        try {
            traffic = new TrafficRecorder();
            WebView webView = host.webView();
            WebViewProfile.configure(activity, webView, traffic, (view, url) -> {
                Uri uri = url == null ? null : Uri.parse(url);
                if (uri != null && CaptureWebViewClient.isCaptureHost(uri.getHost())) {
                    scheduleCapture(SETTLE_AFTER_FINISH_MS);
                }
            });
            hookHandler = WebViewProfile.installDocumentStartHook(activity, webView, traffic);
            webView.loadUrl(targetUrl);
            handler.postDelayed(() -> scheduleCapture(0), FALLBACK_CAPTURE_MS);
        } catch (Exception e) {
            fail(e.toString());
        }
    }

    private void scheduleCapture(long delayMs) {
        if (finished.get() || !captureScheduled.compareAndSet(false, true)) return;
        handler.postDelayed(this::captureNow, Math.max(0L, delayMs));
    }

    private void captureNow() {
        if (finished.get() || host == null) return;
        boolean hookInstalled = hookHandler != null;
        DiagnosticRecorder recorder = new DiagnosticRecorder(
                activity,
                host.webView(),
                traffic,
                hookInstalled,
                host.modeLabel(),
                host.rootView());
        recorder.capture(new DiagnosticRecorder.Callback() {
            @Override
            public void onSuccess(File zip) {
                if (!finished.compareAndSet(false, true)) {
                    CapturePackage.deleteRecursively(zip);
                    return;
                }
                cleanup();
                callback.onSuccess(zip);
            }

            @Override
            public void onFailure(String message) {
                fail(message);
            }
        });
    }

    private void fail(String message) {
        if (!finished.compareAndSet(false, true)) return;
        cleanup();
        callback.onFailure(message);
    }

    private void cleanup() {
        handler.removeCallbacksAndMessages(null);
        if (hookHandler != null) {
            try { hookHandler.remove(); } catch (Exception ignored) {}
            hookHandler = null;
        }
        if (host != null) {
            host.destroy();
            host = null;
        }
    }
}
