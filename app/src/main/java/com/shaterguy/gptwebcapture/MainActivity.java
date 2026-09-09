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
    private Button exportButton;
    private TrafficRecorder trafficRecorder;
    private DiagnosticRecorder diagnosticRecorder;
    private ScriptHandler passiveHookHandler;
    private File pendingZip;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        trafficRecorder = new TrafficRecorder();
        buildUi();
        WebView.setWebContentsDebuggingEnabled(BuildConfig.WEB_CONTENT_DEBUGGING);

        WebViewProfile.configure(this, webView, trafficRecorder, (view, url) -> address.setText(url == null ? "" : url));
        passiveHookHandler = WebViewProfile.installDocumentStartHook(this, webView, trafficRecorder);
        diagnosticRecorder = new DiagnosticRecorder(
                this,
                webView,
                trafficRecorder,
                passiveHookHandler != null,
                "single-user-operated-webview",
                getWindow().getDecorView());
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
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.setClickable(true);
        webView.setLongClickable(true);
        root.addView(webView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(pad, pad, pad, pad);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);

        captureButton = new Button(this);
        captureButton.setText("전체 캡처");
        captureButton.setOnClickListener(v -> startCapture());
        actions.addView(captureButton, new LinearLayout.LayoutParams(0, dp(52), 1f));

        exportButton = new Button(this);
        exportButton.setText("ZIP 저장");
        exportButton.setEnabled(false);
        exportButton.setOnClickListener(v -> exportPendingZip());
        actions.addView(exportButton, new LinearLayout.LayoutParams(0, dp(52), 1f));
        bottom.addView(actions, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        status = new TextView(this);
        status.setText("이 WebView 하나를 직접 조작하세요. 앱은 같은 WebView를 계속 관찰하고, ‘전체 캡처’를 누르면 현재 상태와 누적 진단 기록을 ZIP으로 저장합니다.");
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
            String scheme = uri.getScheme();
            if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
                status.setText("http/https 주소만 직접 입력할 수 있습니다.");
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
        if (current == null || current.isEmpty()) {
            status.setText("캡처할 페이지가 없습니다.");
            return;
        }
        clearPendingZip();
        captureButton.setEnabled(false);
        status.setText("현재 WebView + 누적 DOM/이벤트/네트워크/콘솔/환경을 전방위 캡처 중…");
        diagnosticRecorder.capture(new DiagnosticRecorder.Callback() {
            @Override public void onSuccess(File zip) { finishCapture(zip); }
            @Override public void onFailure(String message) { failCapture(message); }
        });
    }

    private void finishCapture(File zip) {
        pendingZip = zip;
        captureButton.setEnabled(true);
        exportButton.setEnabled(true);
        status.setText("캡처 완료: " + zip.getName() + " (" + zip.length() + " bytes)");
        exportPendingZip();
    }

    private void failCapture(String message) {
        captureButton.setEnabled(true);
        status.setText("캡처 실패: " + SafeRedactor.scrubText(message));
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
        if (passiveHookHandler != null) {
            try { passiveHookHandler.remove(); } catch (Exception ignored) {}
            passiveHookHandler = null;
        }
        if (webView != null) {
            android.webkit.CookieManager.getInstance().flush();
            webView.stopLoading();
            webView.destroy();
        }
        if (pendingZip != null) CapturePackage.deleteRecursively(pendingZip);
        super.onDestroy();
    }

    WebView webViewForInstrumentationTest() { return webView; }
    boolean passiveHookInstalledForInstrumentationTest() { return passiveHookHandler != null; }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
