package com.shaterguy.gptwebcapture;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.webkit.ScriptHandler;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;

public final class MainActivity extends Activity {
    private static final int REQUEST_EXPORT_ZIP = 4101;
    private static final String HOME_URL = "https://chatgpt.com/";

    private WebView webView;
    private EditText address;
    private TextView status;
    private Button captureButton;
    private Button headlessButton;
    private Button exportButton;
    private TrafficRecorder trafficRecorder;
    private DiagnosticRecorder diagnosticRecorder;
    private HeadlessCaptureController headlessCaptureController;
    private File pendingZip;
    private boolean documentStartHookInstalled;
    private ScriptHandler hookScriptHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        trafficRecorder = new TrafficRecorder();
        buildUi();
        WebView.setWebContentsDebuggingEnabled(BuildConfig.WEB_CONTENT_DEBUGGING);
        WebViewProfile.configure(this, webView, trafficRecorder, null);
        hookScriptHandler = WebViewProfile.installDocumentStartHook(this, webView, trafficRecorder);
        documentStartHookInstalled = hookScriptHandler != null;
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
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(pad, pad, pad, pad);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);

        captureButton = new Button(this);
        captureButton.setText("화면 캡처");
        captureButton.setOnClickListener(v -> startVisibleCapture());
        actions.addView(captureButton, new LinearLayout.LayoutParams(0, dp(52), 1f));

        headlessButton = new Button(this);
        headlessButton.setText("백그라운드 캡처");
        headlessButton.setOnClickListener(v -> startHeadlessCapture());
        actions.addView(headlessButton, new LinearLayout.LayoutParams(0, dp(52), 1.25f));

        exportButton = new Button(this);
        exportButton.setText("ZIP 저장");
        exportButton.setEnabled(false);
        exportButton.setOnClickListener(v -> exportPendingZip());
        actions.addView(exportButton, new LinearLayout.LayoutParams(0, dp(52), 1f));
        bottom.addView(actions, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        status = new TextView(this);
        status.setText("로그인 후 화면 캡처 또는 자동화 앱과 같은 1440×900 / 160 dpi 백그라운드 캡처를 실행하세요.");
        status.setPadding(dp(8), dp(4), dp(4), 0);
        status.setMaxLines(4);
        bottom.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(bottom, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
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

    private String currentCaptureUrl() {
        String current = webView.getUrl();
        Uri uri = current == null ? null : Uri.parse(current);
        if (uri == null || !CaptureWebViewClient.isCaptureHost(uri.getHost())) return null;
        return current;
    }

    private void startVisibleCapture() {
        String current = currentCaptureUrl();
        if (current == null) {
            status.setText("캡처는 chatgpt.com 화면에서만 실행됩니다.");
            return;
        }
        clearPendingZip();
        setCaptureButtonsEnabled(false);
        status.setText("화면 WebView 전방위 캡처 중…");
        diagnosticRecorder.capture(new DiagnosticRecorder.Callback() {
            @Override public void onSuccess(File zip) { finishCapture(zip, "화면 캡처 완료"); }
            @Override public void onFailure(String message) { failCapture(message); }
        });
    }

    private void startHeadlessCapture() {
        String current = currentCaptureUrl();
        if (current == null) {
            status.setText("백그라운드 캡처는 chatgpt.com 화면에서만 실행됩니다.");
            return;
        }
        clearPendingZip();
        setCaptureButtonsEnabled(false);
        status.setText("자동화 앱과 같은 VirtualDisplay WebView를 생성해 현재 URL을 재로딩하고 전방위 캡처 중…");
        headlessCaptureController = new HeadlessCaptureController(this, current, new HeadlessCaptureController.Callback() {
            @Override
            public void onSuccess(File zip) {
                headlessCaptureController = null;
                finishCapture(zip, "백그라운드 캡처 완료");
            }

            @Override
            public void onFailure(String message) {
                headlessCaptureController = null;
                failCapture(message);
            }
        });
        headlessCaptureController.start();
    }

    private void finishCapture(File zip, String prefix) {
        pendingZip = zip;
        setCaptureButtonsEnabled(true);
        exportButton.setEnabled(true);
        status.setText(prefix + ": " + zip.getName() + " (" + zip.length() + " bytes)");
        exportPendingZip();
    }

    private void failCapture(String message) {
        setCaptureButtonsEnabled(true);
        status.setText("캡처 실패: " + SafeRedactor.scrubText(message));
    }

    private void setCaptureButtonsEnabled(boolean enabled) {
        captureButton.setEnabled(enabled);
        headlessButton.setEnabled(enabled);
    }

    private void clearPendingZip() {
        if (pendingZip != null) {
            CapturePackage.deleteRecursively(pendingZip);
            pendingZip = null;
        }
        exportButton.setEnabled(false);
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

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (headlessCaptureController != null) {
            headlessCaptureController.cancel();
            headlessCaptureController = null;
        }
        if (hookScriptHandler != null) {
            try { hookScriptHandler.remove(); } catch (Exception ignored) {}
            hookScriptHandler = null;
        }
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
