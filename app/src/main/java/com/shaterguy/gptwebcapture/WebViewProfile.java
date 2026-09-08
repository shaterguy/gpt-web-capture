package com.shaterguy.gptwebcapture;

import android.app.Activity;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

final class WebViewProfile {
    private WebViewProfile() {}

    static void configure(Activity activity, WebView webView, TrafficRecorder recorder,
                          CaptureWebViewClient.PageListener pageListener) {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setSaveFormData(false);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new CaptureWebViewClient(activity, recorder, pageListener));
        webView.setWebChromeClient(new CaptureWebChromeClient(recorder));
    }

    static ScriptHandler installDocumentStartHook(Activity activity, WebView webView, TrafficRecorder recorder) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return null;
        try {
            String hook = readAsset(activity, "hook.js");
            Set<String> origins = new LinkedHashSet<>();
            origins.add("https://chatgpt.com");
            origins.add("https://*.chatgpt.com");
            return WebViewCompat.addDocumentStartJavaScript(webView, hook, origins);
        } catch (Exception e) {
            if (recorder != null) recorder.recordPage("documentStartHookFailed:" + SafeRedactor.scrubText(e.toString()), webView.getUrl());
            return null;
        }
    }

    static String readAsset(Activity activity, String name) throws Exception {
        try (InputStream in = activity.getAssets().open(name); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
