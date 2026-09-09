package com.shaterguy.gptwebcapture;

import android.app.Activity;
import android.net.Uri;
import android.net.http.SslError;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.Locale;

class CaptureWebViewClient extends WebViewClient {
    interface PageListener {
        void onPageFinished(WebView view, String url);
    }

    private final Activity activity;
    private final TrafficRecorder recorder;
    private final PageListener pageListener;

    CaptureWebViewClient(Activity activity, TrafficRecorder recorder) {
        this(activity, recorder, null);
    }

    CaptureWebViewClient(Activity activity, TrafficRecorder recorder, PageListener pageListener) {
        this.activity = activity;
        this.recorder = recorder;
        this.pageListener = pageListener;
    }

    static boolean isCaptureHost(String host) {
        if (host == null) return false;
        String value = host.toLowerCase(Locale.ROOT);
        return value.equals("chatgpt.com") || value.endsWith(".chatgpt.com")
                || value.equals("openai.com") || value.endsWith(".openai.com");
    }

    static boolean isAllowedWebViewHost(String host) {
        return host != null;
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        // Match the proven SelfRun login browser: never hijack normal navigation.
        if (request != null && request.isForMainFrame()) {
            Uri uri = request.getUrl();
            recorder.recordPage("navigation:" + (uri == null ? "" : uri.getScheme()),
                    uri == null ? "" : uri.toString());
        }
        return false;
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        recorder.recordRequest(request, "webview");
        return null;
    }

    @Override
    public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
        recorder.recordPage("started", url);
        super.onPageStarted(view, url, favicon);
    }

    @Override
    public void onPageCommitVisible(WebView view, String url) {
        recorder.recordPage("commitVisible", url);
        super.onPageCommitVisible(view, url);
    }

    @Override
    public void onPageFinished(WebView view, String url) {
        recorder.recordPage("finished", url);
        super.onPageFinished(view, url);
        if (pageListener != null) pageListener.onPageFinished(view, url);
    }

    @Override
    public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
        int code = error == null ? 0 : error.getErrorCode();
        String description = error == null || error.getDescription() == null ? "" : error.getDescription().toString();
        recorder.recordResourceError(request, code, description);
        super.onReceivedError(view, request, error);
    }

    @Override
    public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
        recorder.recordHttpError(request, errorResponse);
        super.onReceivedHttpError(view, request, errorResponse);
    }

    @Override
    public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
        recorder.recordSslError(error);
        if (handler != null) handler.cancel();
    }
}
