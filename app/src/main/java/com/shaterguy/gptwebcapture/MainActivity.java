package com.shaterguy.gptwebcapture;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

public final class MainActivity extends Activity {
    private static final int REQUEST_EXPORT_ZIP = 4101;
    private static final String HOME_URL = "https://chatgpt.com/";

    private WebView webView;
    private EditText address;
    private TextView status;
    private Button captureButton;
    private Button exportButton;
    private TrafficRecorder trafficRecorder;
    private DiagnosticRecorder diagnosticRecorder;
    private File pendingZip;
    private boolean documentStartHookInstalled;
    private ScriptHandler hookScriptHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        trafficRecorder = new TrafficRecorder();
        buildUi();
        configureWebView();
        documentStartHookInstalled = installDocumentStartHook();
        diagnosticRecorder = new DiagnosticRecorder(this, webView, trafficRecorder, documentStartHookInstalled);
        webView.loadUrl(HOME_URL);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(6);
        top.setPadding(pad, pad, pad, pad);

        address = new EditText(this);
        address.setSingleLine(true);
        address.setText(HOME_URL);
        address.setHint("https://chatgpt.com/...");
        top.addView(address, new LinearLayout.LayoutParams(0, dp(48), 1f));

        Button go = new Button(this);
        go.setText("이동");
        go.setOnClickListener(v -> navigate(address.getText().toString()));
        top.addView(go, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));

        Button home = new Button(this);
        home.setText("홈");
        home.setOnClickListener(v -> navigate(HOME_URL));
        top.addView(home, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        webView = new WebView(this);
        root.addView(webView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(pad, pad, pad, pad);

        captureButton = new Button(this);
        captureButton.setText("전체 캡처");
        captureButton.setOnClickListener(v -> startCapture());
        bottom.addView(captureButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)));

        exportButton = new Button(this);
        exportButton.setText("ZIP 저장");
        exportButton.setEnabled(false);
        exportButton.setOnClickListener(v -> exportPendingZip());
        bottom.addView(exportButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)));

        status = new TextView(this);
        status.setText("ChatGPT에 로그인한 뒤 문제가 발생한 화면에서 ‘전체 캡처’를 누르세요.");
        status.setPadding(dp(8), 0, dp(4), 0);
        status.setMaxLines(3);
        bottom.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(bottom, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
    }

    private void configureWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.WEB_CONTENT_DEBUGGING);
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

        webView.setWebViewClient(new CaptureWebViewClient(this, trafficRecorder));
        webView.setWebChromeClient(new CaptureWebChromeClient(trafficRecorder));
    }

    private boolean installDocumentStartHook() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return false;
        try {
            String hook = readAsset("hook.js");
            Set<String> origins = new LinkedHashSet<>();
            origins.add("https://chatgpt.com");
            origins.add("https://*.chatgpt.com");
            hookScriptHandler = WebViewCompat.addDocumentStartJavaScript(webView, hook, origins);
            return hookScriptHandler != null;
        } catch (Exception e) {
            trafficRecorder.recordPage("documentStartHookFailed:" + SafeRedactor.scrubText(e.toString()), webView.getUrl());
            return false;
        }
    }

    private void navigate(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) value = HOME_URL;
        try {
            Uri uri = Uri.parse(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !CaptureWebViewClient.isAllowedWebViewHost(uri.getHost())) {
                status.setText("앱 내 직접 이동은 chatgpt.com / openai.com HTTPS 주소만 허용됩니다.");
                return;
            }
            address.setText(value);
            webView.loadUrl(value);
        } catch (Exception e) {
            status.setText("주소 오류: " + e.getClass().getSimpleName());
        }
    }

    private void startCapture() {
        String current = webView.getUrl();
        Uri uri = current == null ? null : Uri.parse(current);
        if (uri == null || !CaptureWebViewClient.isCaptureHost(uri.getHost())) {
            status.setText("캡처는 chatgpt.com 화면에서만 실행됩니다.");
            return;
        }
        if (pendingZip != null) {
            CapturePackage.deleteRecursively(pendingZip);
            pendingZip = null;
            exportButton.setEnabled(false);
        }
        captureButton.setEnabled(false);
        status.setText("캡처 중: 화면 · DOM · Shadow DOM · frame · CSSOM · 성능 · 네트워크 · 콘솔 · 접근성 · 환경…");
        diagnosticRecorder.capture(new DiagnosticRecorder.Callback() {
            @Override
            public void onSuccess(File zip) {
                pendingZip = zip;
                captureButton.setEnabled(true);
                exportButton.setEnabled(true);
                status.setText("캡처 완료: " + zip.getName() + " (" + zip.length() + " bytes)");
                exportPendingZip();
            }

            @Override
            public void onFailure(String message) {
                captureButton.setEnabled(true);
                status.setText("캡처 실패: " + SafeRedactor.scrubText(message));
            }
        });
    }

    private void exportPendingZip() {
        if (pendingZip == null || !pendingZip.isFile()) {
            status.setText("저장할 ZIP이 없습니다.");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, pendingZip.getName());
        startActivityForResult(intent, REQUEST_EXPORT_ZIP);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_EXPORT_ZIP) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            status.setText("ZIP 저장이 취소되었습니다. ‘ZIP 저장’을 누르면 다시 내보낼 수 있습니다.");
            return;
        }
        final Uri target = data.getData();
        final File source = pendingZip;
        exportButton.setEnabled(false);
        status.setText("ZIP 저장 중…");
        new Thread(() -> {
            String error = null;
            try (InputStream in = new java.io.FileInputStream(source);
                 OutputStream out = getContentResolver().openOutputStream(target, "w")) {
                if (out == null) throw new IllegalStateException("output stream unavailable");
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                out.flush();
            } catch (Exception e) {
                error = e.toString();
            }
            final String finalError = error;
            runOnUiThread(() -> {
                if (finalError == null) {
                    status.setText("ZIP 저장 완료");
                    CapturePackage.deleteRecursively(source);
                    if (pendingZip == source) pendingZip = null;
                } else {
                    status.setText("ZIP 저장 실패: " + SafeRedactor.scrubText(finalError));
                    exportButton.setEnabled(true);
                }
            });
        }, "capture-export").start();
    }

    String readAsset(String name) throws Exception {
        try (InputStream in = getAssets().open(name); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        if (pendingZip != null) CapturePackage.deleteRecursively(pendingZip);
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
