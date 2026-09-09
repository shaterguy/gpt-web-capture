package com.shaterguy.gptwebcapture;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Picture;
import android.graphics.Rect;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.http.SslCertificate;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebBackForwardList;
import android.webkit.WebHistoryItem;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Queue;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class DiagnosticRecorder {
    interface Callback {
        void onSuccess(File zip);
        void onFailure(String message);
    }

    private static final String BRIDGE_NAME = "GPTCaptureBridge";
    private static final long CAPTURE_TIMEOUT_MS = 45000L;
    private static final int MAX_FULL_SCREENSHOT_PIXELS = 24_000_000;
    private static final int MAX_ACCESSIBILITY_NODES = 20_000;

    private final MainActivity activity;
    private final WebView webView;
    private final TrafficRecorder traffic;
    private final boolean documentStartHookInstalled;
    private final String captureMode;
    private final View captureRoot;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final PersistentCaptureBridge persistentBridge = new PersistentCaptureBridge();
    private boolean bridgeInstalled;

    DiagnosticRecorder(MainActivity activity, WebView webView, TrafficRecorder traffic,
                       boolean documentStartHookInstalled) {
        this(activity, webView, traffic, documentStartHookInstalled,
                "visible-activity-webview", activity.getWindow().getDecorView());
    }

    DiagnosticRecorder(MainActivity activity, WebView webView, TrafficRecorder traffic,
                       boolean documentStartHookInstalled, String captureMode, View captureRoot) {
        this.activity = activity;
        this.webView = webView;
        this.traffic = traffic;
        this.documentStartHookInstalled = documentStartHookInstalled;
        this.captureMode = captureMode == null || captureMode.isEmpty() ? "unknown" : captureMode;
        this.captureRoot = captureRoot == null ? webView : captureRoot;
    }

    /** Must be called before the first page load so the bridge is available in every current page. */
    void installPersistentBridge() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::installPersistentBridge);
            return;
        }
        if (bridgeInstalled) return;
        webView.addJavascriptInterface(persistentBridge, BRIDGE_NAME);
        bridgeInstalled = true;
    }

    void uninstallPersistentBridge() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::uninstallPersistentBridge);
            return;
        }
        persistentBridge.clear();
        if (bridgeInstalled) {
            webView.removeJavascriptInterface(BRIDGE_NAME);
            bridgeInstalled = false;
        }
    }

    void capture(Callback callback) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> capture(callback));
            return;
        }
        if (!bridgeInstalled) {
            callback.onFailure("persistent capture bridge is not installed before page load");
            return;
        }
        if (persistentBridge.isActive()) {
            callback.onFailure("capture already in progress");
            return;
        }
        String sourceUrl = webView.getUrl();
        try {
            CapturePackage pack = new CapturePackage(activity, sourceUrl);
            captureNativeArtifacts(pack);
            SessionState state = new SessionState(pack, callback, sourceUrl);
            startWebArchive(state);
            startJavascriptCapture(state);
            mainHandler.postDelayed(state::timeout, CAPTURE_TIMEOUT_MS);
        } catch (Exception e) {
            callback.onFailure(e.toString());
        }
    }

    private void captureNativeArtifacts(CapturePackage pack) {
        attempt(pack, "native/environment.json", () -> pack.writeJson("native/environment.json", nativeEnvironment()));
        attempt(pack, "native/history.json", () -> pack.writeJson("native/history.json", historySnapshot()));
        attempt(pack, "native/cookies-metadata.json", () -> {
            JSONObject out = new JSONObject();
            out.put("url", SafeRedactor.redactUrl(webView.getUrl()));
            out.put("cookies", SafeRedactor.cookieMetadata(CookieManager.getInstance().getCookie(webView.getUrl())));
            out.put("rawValuesPersisted", false);
            pack.writeJson("native/cookies-metadata.json", out);
        });
        attempt(pack, "accessibility/android-tree.json", () -> pack.writeJson("accessibility/android-tree.json", accessibilitySnapshot()));
        attempt(pack, "screenshots/webview-viewport.png", () -> captureViewBitmap(pack, "screenshots/webview-viewport.png", webView));
        attempt(pack, "screenshots/context-root.png", () -> captureViewBitmap(pack, "screenshots/context-root.png", captureRoot));
        attempt(pack, "screenshots/webview-full-best-effort.png", () -> captureFullPicture(pack));
        attempt(pack, "limitations.json", () -> pack.writeJson("limitations.json", limitations()));
    }

    private void startWebArchive(SessionState state) {
        try {
            File archive = state.pack.fileFor("page/webarchive.mht");
            webView.saveWebArchive(archive.getAbsolutePath(), false, filename -> {
                if (filename == null || !archive.isFile() || archive.length() == 0) {
                    state.pack.recordFailure("page/webarchive.mht", "WebView.saveWebArchive returned no file");
                }
                state.archiveDone();
            });
        } catch (Exception e) {
            state.pack.recordFailure("page/webarchive.mht", e.toString());
            state.archiveDone();
        }
    }

    private void startJavascriptCapture(SessionState state) {
        String token = UUID.randomUUID().toString();
        if (!persistentBridge.activate(token, state)) {
            state.pack.recordFailure("web/javascript-capture", "persistent bridge is busy");
            state.javascriptDone();
            return;
        }
        try {
            String source = WebViewProfile.readAsset(activity, "capture.js");
            webView.evaluateJavascript(source + "\n;typeof window.__GPT_WEB_CAPTURE__;", result -> {
                String run = "(() => { try {" +
                        "if(!window.__GPT_WEB_CAPTURE__) { window." + BRIDGE_NAME + ".fail(" + JSONObject.quote(token) + ",'capture runtime missing'); return 'missing'; }" +
                        "Promise.resolve(window.__GPT_WEB_CAPTURE__.run('" + BRIDGE_NAME + "'," + JSONObject.quote(token) + "))" +
                        ".catch(e=>window." + BRIDGE_NAME + ".fail(" + JSONObject.quote(token) + ",String(e&&e.stack||e)));" +
                        "return 'started'; } catch(e) { window." + BRIDGE_NAME + ".fail(" + JSONObject.quote(token) + ",String(e&&e.stack||e)); return 'error'; } })()";
                webView.evaluateJavascript(run, ignored -> {});
            });
        } catch (Exception e) {
            state.pack.recordFailure("web/javascript-capture", e.toString());
            persistentBridge.cancel(state);
            state.javascriptDone();
        }
    }

    private JSONObject nativeEnvironment() throws Exception {
        JSONObject root = new JSONObject();
        root.put("captureMode", captureMode);

        JSONObject app = new JSONObject();
        app.put("applicationId", BuildConfig.APPLICATION_ID);
        app.put("versionName", BuildConfig.VERSION_NAME);
        app.put("versionCode", BuildConfig.VERSION_CODE);
        app.put("buildType", BuildConfig.BUILD_TYPE);
        app.put("flavor", BuildConfig.FLAVOR);
        app.put("webContentsDebugging", BuildConfig.WEB_CONTENT_DEBUGGING);
        root.put("app", app);

        JSONObject device = new JSONObject();
        device.put("manufacturer", Build.MANUFACTURER);
        device.put("brand", Build.BRAND);
        device.put("model", Build.MODEL);
        device.put("device", Build.DEVICE);
        device.put("product", Build.PRODUCT);
        device.put("hardware", Build.HARDWARE);
        device.put("fingerprint", Build.FINGERPRINT);
        device.put("sdkInt", Build.VERSION.SDK_INT);
        device.put("release", Build.VERSION.RELEASE);
        device.put("securityPatch", Build.VERSION.SECURITY_PATCH);
        device.put("supportedAbis", new JSONArray(Build.SUPPORTED_ABIS));
        root.put("device", device);

        PackageInfo webViewPackage = WebView.getCurrentWebViewPackage();
        JSONObject webViewInfo = new JSONObject();
        if (webViewPackage != null) {
            webViewInfo.put("packageName", webViewPackage.packageName);
            webViewInfo.put("versionName", webViewPackage.versionName);
            webViewInfo.put("longVersionCode", webViewPackage.getLongVersionCode());
        }
        webViewInfo.put("documentStartHookInstalled", documentStartHookInstalled);
        webViewInfo.put("persistentCaptureBridgeInstalled", bridgeInstalled);
        webViewInfo.put("url", SafeRedactor.redactUrl(webView.getUrl()));
        webViewInfo.put("originalUrl", SafeRedactor.redactUrl(webView.getOriginalUrl()));
        webViewInfo.put("title", SafeRedactor.scrubText(webView.getTitle()));
        webViewInfo.put("progress", webView.getProgress());
        webViewInfo.put("scale", webView.getScale());
        webViewInfo.put("contentHeightCss", webView.getContentHeight());
        webViewInfo.put("widthPx", webView.getWidth());
        webViewInfo.put("heightPx", webView.getHeight());
        webViewInfo.put("scrollX", webView.getScrollX());
        webViewInfo.put("scrollY", webView.getScrollY());
        webViewInfo.put("shown", webView.isShown());
        webViewInfo.put("attachedToWindow", webView.isAttachedToWindow());
        webViewInfo.put("focused", webView.isFocused());
        webViewInfo.put("windowFocused", webView.hasWindowFocus());
        webViewInfo.put("visibility", webView.getVisibility());
        webViewInfo.put("windowVisibility", webView.getWindowVisibility());
        root.put("webView", webViewInfo);

        WebSettings settings = webView.getSettings();
        JSONObject webSettings = new JSONObject();
        webSettings.put("userAgent", settings.getUserAgentString());
        webSettings.put("javaScriptEnabled", settings.getJavaScriptEnabled());
        webSettings.put("domStorageEnabled", settings.getDomStorageEnabled());
        webSettings.put("databaseEnabled", settings.getDatabaseEnabled());
        webSettings.put("allowFileAccess", settings.getAllowFileAccess());
        webSettings.put("allowContentAccess", settings.getAllowContentAccess());
        webSettings.put("allowFileAccessFromFileUrls", settings.getAllowFileAccessFromFileURLs());
        webSettings.put("allowUniversalAccessFromFileUrls", settings.getAllowUniversalAccessFromFileURLs());
        webSettings.put("mixedContentMode", settings.getMixedContentMode());
        webSettings.put("mediaPlaybackRequiresUserGesture", settings.getMediaPlaybackRequiresUserGesture());
        webSettings.put("safeBrowsingEnabled", settings.getSafeBrowsingEnabled());
        root.put("webSettings", webSettings);

        root.put("activityDisplay", displayMetrics(activity.getResources().getDisplayMetrics()));
        root.put("webViewDisplay", displayMetrics(webView.getResources().getDisplayMetrics()));

        JSONObject runtime = new JSONObject();
        Runtime jvm = Runtime.getRuntime();
        runtime.put("availableProcessors", jvm.availableProcessors());
        runtime.put("maxMemory", jvm.maxMemory());
        runtime.put("totalMemory", jvm.totalMemory());
        runtime.put("freeMemory", jvm.freeMemory());
        runtime.put("cacheUsableSpace", activity.getCacheDir().getUsableSpace());
        runtime.put("locale", Locale.getDefault().toLanguageTag());
        runtime.put("timeZone", TimeZone.getDefault().getID());
        runtime.put("capturedAt", Instant.now().toString());
        root.put("runtime", runtime);

        root.put("network", networkEnvironment());
        root.put("certificate", certificateEnvironment());
        return root;
    }

    private JSONObject displayMetrics(DisplayMetrics metrics) throws Exception {
        JSONObject display = new JSONObject();
        display.put("widthPixels", metrics.widthPixels);
        display.put("heightPixels", metrics.heightPixels);
        display.put("density", metrics.density);
        display.put("densityDpi", metrics.densityDpi);
        display.put("scaledDensity", metrics.scaledDensity);
        display.put("xdpi", metrics.xdpi);
        display.put("ydpi", metrics.ydpi);
        return display;
    }

    private JSONObject networkEnvironment() throws Exception {
        JSONObject out = new JSONObject();
        ConnectivityManager manager = (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return out;
        Network active = manager.getActiveNetwork();
        NetworkCapabilities caps = active == null ? null : manager.getNetworkCapabilities(active);
        out.put("activeNetworkPresent", active != null);
        out.put("metered", manager.isActiveNetworkMetered());
        if (caps != null) {
            JSONArray transports = new JSONArray();
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transports.put("wifi");
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transports.put("cellular");
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transports.put("ethernet");
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) transports.put("vpn");
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) transports.put("bluetooth");
            out.put("transports", transports);
            out.put("internet", caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
            out.put("validated", caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
            out.put("captivePortal", caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL));
            out.put("notMetered", caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
            out.put("downstreamKbps", caps.getLinkDownstreamBandwidthKbps());
            out.put("upstreamKbps", caps.getLinkUpstreamBandwidthKbps());
        }
        return out;
    }

    private JSONObject certificateEnvironment() throws Exception {
        JSONObject out = new JSONObject();
        SslCertificate cert = webView.getCertificate();
        if (cert == null) return out;
        if (cert.getIssuedTo() != null) {
            JSONObject issuedTo = new JSONObject();
            issuedTo.put("cName", cert.getIssuedTo().getCName());
            issuedTo.put("oName", cert.getIssuedTo().getOName());
            issuedTo.put("uName", cert.getIssuedTo().getUName());
            out.put("issuedTo", issuedTo);
        }
        if (cert.getIssuedBy() != null) {
            JSONObject issuedBy = new JSONObject();
            issuedBy.put("cName", cert.getIssuedBy().getCName());
            issuedBy.put("oName", cert.getIssuedBy().getOName());
            issuedBy.put("uName", cert.getIssuedBy().getUName());
            out.put("issuedBy", issuedBy);
        }
        out.put("validNotBefore", cert.getValidNotBeforeDate() == null ? JSONObject.NULL : cert.getValidNotBeforeDate().getTime());
        out.put("validNotAfter", cert.getValidNotAfterDate() == null ? JSONObject.NULL : cert.getValidNotAfterDate().getTime());
        return out;
    }

    private JSONObject historySnapshot() throws Exception {
        JSONObject out = new JSONObject();
        WebBackForwardList list = webView.copyBackForwardList();
        out.put("currentIndex", list.getCurrentIndex());
        JSONArray items = new JSONArray();
        for (int i = 0; i < list.getSize(); i++) {
            WebHistoryItem item = list.getItemAtIndex(i);
            JSONObject row = new JSONObject();
            row.put("index", i);
            row.put("url", SafeRedactor.redactUrl(item == null ? null : item.getUrl()));
            row.put("originalUrl", SafeRedactor.redactUrl(item == null ? null : item.getOriginalUrl()));
            row.put("title", SafeRedactor.scrubText(item == null ? null : item.getTitle()));
            items.put(row);
        }
        out.put("items", items);
        return out;
    }

    private JSONObject accessibilitySnapshot() throws Exception {
        JSONObject out = new JSONObject();
        JSONArray nodes = new JSONArray();
        Queue<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        AccessibilityNodeInfo root = webView.createAccessibilityNodeInfo();
        if (root != null) queue.add(root);
        int count = 0;
        int dropped = 0;
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.remove();
            if (count >= MAX_ACCESSIBILITY_NODES) {
                dropped += 1 + node.getChildCount();
                continue;
            }
            JSONObject row = new JSONObject();
            row.put("index", count++);
            row.put("className", string(node.getClassName()));
            row.put("packageName", string(node.getPackageName()));
            row.put("viewId", string(node.getViewIdResourceName()));
            row.put("text", SafeRedactor.scrubText(string(node.getText())));
            row.put("contentDescription", SafeRedactor.scrubText(string(node.getContentDescription())));
            row.put("clickable", node.isClickable());
            row.put("editable", node.isEditable());
            row.put("focusable", node.isFocusable());
            row.put("focused", node.isFocused());
            row.put("accessibilityFocused", node.isAccessibilityFocused());
            row.put("enabled", node.isEnabled());
            row.put("visibleToUser", node.isVisibleToUser());
            row.put("scrollable", node.isScrollable());
            row.put("selected", node.isSelected());
            row.put("password", node.isPassword());
            row.put("childCount", node.getChildCount());
            Rect rect = new Rect();
            node.getBoundsInScreen(rect);
            JSONObject bounds = new JSONObject();
            bounds.put("left", rect.left);
            bounds.put("top", rect.top);
            bounds.put("right", rect.right);
            bounds.put("bottom", rect.bottom);
            row.put("boundsInScreen", bounds);
            JSONArray actions = new JSONArray();
            for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) {
                JSONObject actionJson = new JSONObject();
                actionJson.put("id", action.getId());
                actionJson.put("label", string(action.getLabel()));
                actions.put(actionJson);
            }
            row.put("actions", actions);
            nodes.put(row);
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        out.put("nodes", nodes);
        out.put("droppedAfterLimit", dropped);
        out.put("maxNodes", MAX_ACCESSIBILITY_NODES);
        return out;
    }

    private static String string(CharSequence value) { return value == null ? "" : value.toString(); }
    private static String string(String value) { return value == null ? "" : value; }

    private void captureViewBitmap(CapturePackage pack, String path, View view) throws Exception {
        int width = view.getWidth();
        int height = view.getHeight();
        if (width <= 0 || height <= 0) throw new IOException("view has no drawable size");
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            view.draw(canvas);
            pack.writeBitmap(path, bitmap);
        } finally {
            bitmap.recycle();
        }
    }

    @SuppressWarnings("deprecation")
    private void captureFullPicture(CapturePackage pack) throws Exception {
        Picture picture = webView.capturePicture();
        if (picture == null || picture.getWidth() <= 0 || picture.getHeight() <= 0) throw new IOException("capturePicture returned no content");
        double pixels = (double) picture.getWidth() * picture.getHeight();
        double scale = pixels > MAX_FULL_SCREENSHOT_PIXELS ? Math.sqrt(MAX_FULL_SCREENSHOT_PIXELS / pixels) : 1.0;
        int width = Math.max(1, (int) Math.round(picture.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(picture.getHeight() * scale));
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            canvas.scale((float) scale, (float) scale);
            picture.draw(canvas);
            pack.writeBitmap("screenshots/webview-full-best-effort.png", bitmap);
            JSONObject meta = new JSONObject();
            meta.put("sourceWidth", picture.getWidth());
            meta.put("sourceHeight", picture.getHeight());
            meta.put("outputWidth", width);
            meta.put("outputHeight", height);
            meta.put("scale", scale);
            pack.writeJson("screenshots/webview-full-best-effort.json", meta);
        } finally {
            bitmap.recycle();
        }
    }

    private JSONObject limitations() throws Exception {
        JSONObject out = new JSONObject();
        out.put("networkRequestBodies", "WebView interception does not expose request bodies; they are intentionally not replayed or proxied.");
        out.put("networkResponseBodies", "Responses are not replaced/proxied, so raw response bodies are not duplicated into the capture.");
        out.put("crossOriginFrames", "Cross-origin iframe metadata is captured, but same-origin policy prevents DOM content access.");
        out.put("closedShadowRoots", "Closed ShadowRoot contents are intentionally not intercepted because this single user-operated WebView uses passive telemetry only; open roots remain capturable.");
        out.put("browserProtocol", "The app does not attach Chrome DevTools Protocol internally; CDP-only traces and the Chromium AX protocol tree are unavailable.");
        out.put("secretValues", "Structured diagnostic outputs redact authentication secrets; raw MHT is a private page archive and may contain rendered application source/state, so the ZIP must be treated as sensitive diagnostic data.");
        out.put("fullPageScreenshot", "WebView.capturePicture is best-effort and pixel-capped to avoid process OOM; viewport and capture-context root screenshots are recorded separately.");
        return out;
    }

    private void attempt(CapturePackage pack, String artifact, ThrowingAction action) {
        try { action.run(); }
        catch (Exception e) { pack.recordFailure(artifact, e.toString()); }
    }

    private interface ThrowingAction { void run() throws Exception; }

    private final class SessionState {
        final CapturePackage pack;
        final Callback callback;
        final String sourceUrl;
        final AtomicBoolean jsComplete = new AtomicBoolean(false);
        final AtomicBoolean archiveComplete = new AtomicBoolean(false);
        final AtomicBoolean finalized = new AtomicBoolean(false);
        final AtomicInteger remaining = new AtomicInteger(2);

        SessionState(CapturePackage pack, Callback callback, String sourceUrl) {
            this.pack = pack;
            this.callback = callback;
            this.sourceUrl = sourceUrl;
        }

        void javascriptDone() {
            if (jsComplete.compareAndSet(false, true)) partDone();
        }

        void archiveDone() {
            if (archiveComplete.compareAndSet(false, true)) partDone();
        }

        void partDone() {
            if (remaining.decrementAndGet() <= 0) finalizeCapture();
        }

        void timeout() {
            if (!jsComplete.get()) {
                persistentBridge.cancel(this);
                pack.recordFailure("web/javascript-capture", "capture timeout");
                javascriptDone();
            }
            if (!archiveComplete.get()) {
                pack.recordFailure("page/webarchive.mht", "capture timeout");
                archiveDone();
            }
        }

        void finalizeCapture() {
            if (!finalized.compareAndSet(false, true)) return;
            mainHandler.post(() -> {
                try {
                    pack.writeJson("network/events.json", traffic.networkSnapshot());
                    pack.writeJson("console/events.json", traffic.consoleSnapshot());
                    JSONObject summary = new JSONObject();
                    summary.put("url", SafeRedactor.redactUrl(sourceUrl));
                    summary.put("title", SafeRedactor.scrubText(webView.getTitle()));
                    summary.put("captureMode", captureMode);
                    summary.put("documentStartHookInstalled", documentStartHookInstalled);
                    summary.put("persistentCaptureBridgeInstalled", bridgeInstalled);
                    summary.put("javascriptCompleted", jsComplete.get());
                    summary.put("webArchiveCompleted", archiveComplete.get());
                    new Thread(() -> {
                        try {
                            File zip = pack.buildZip(summary);
                            mainHandler.post(() -> callback.onSuccess(zip));
                        } catch (Exception e) {
                            mainHandler.post(() -> callback.onFailure(e.toString()));
                        }
                    }, "capture-zip").start();
                } catch (Exception e) {
                    callback.onFailure(e.toString());
                }
            });
        }
    }

    private final class PersistentCaptureBridge {
        private SessionState activeState;
        private String activeToken;
        private final java.util.HashMap<String, Integer> nextSequence = new java.util.HashMap<>();
        private boolean finished;

        synchronized boolean activate(String token, SessionState state) {
            if (activeState != null) return false;
            activeToken = token;
            activeState = state;
            nextSequence.clear();
            finished = false;
            return true;
        }

        synchronized boolean isActive() {
            return activeState != null;
        }

        synchronized void cancel(SessionState state) {
            if (activeState == state) clearLocked();
        }

        synchronized void clear() {
            clearLocked();
        }

        private void clearLocked() {
            activeState = null;
            activeToken = null;
            nextSequence.clear();
            finished = false;
        }

        @JavascriptInterface
        public synchronized void push(String suppliedToken, String path, int sequence, int total, String chunk) {
            SessionState state = activeState;
            if (state == null || activeToken == null || !activeToken.equals(suppliedToken) || finished) return;
            try {
                if (total < 1 || sequence < 0 || sequence >= total) throw new IOException("invalid chunk coordinates");
                int expected = nextSequence.containsKey(path) ? nextSequence.get(path) : 0;
                if (sequence != expected) throw new IOException("out-of-order chunk for " + path + ": expected=" + expected + " actual=" + sequence);
                String target = "web/" + path;
                state.pack.appendTextChunk(target, chunk, sequence == 0);
                nextSequence.put(path, sequence + 1);
            } catch (Exception e) {
                state.pack.recordFailure("web/" + path, e.toString());
            }
        }

        @JavascriptInterface
        public void complete(String suppliedToken, String summaryJson) {
            final SessionState state;
            synchronized (this) {
                if (activeState == null || activeToken == null || !activeToken.equals(suppliedToken) || finished) return;
                finished = true;
                state = activeState;
            }
            try {
                state.pack.writeText("web/capture-runtime-summary.json", summaryJson == null ? "{}" : summaryJson);
            } catch (Exception e) {
                state.pack.recordFailure("web/capture-runtime-summary.json", e.toString());
            }
            synchronized (this) { clearLocked(); }
            mainHandler.post(state::javascriptDone);
        }

        @JavascriptInterface
        public void fail(String suppliedToken, String error) {
            final SessionState state;
            synchronized (this) {
                if (activeState == null || activeToken == null || !activeToken.equals(suppliedToken) || finished) return;
                finished = true;
                state = activeState;
            }
            state.pack.recordFailure("web/javascript-capture", error);
            synchronized (this) { clearLocked(); }
            mainHandler.post(state::javascriptDone);
        }
    }
}
