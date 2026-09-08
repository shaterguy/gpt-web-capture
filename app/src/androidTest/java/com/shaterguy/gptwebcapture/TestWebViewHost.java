package com.shaterguy.gptwebcapture;

import android.app.Presentation;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.view.Surface;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.WebView;
import android.widget.FrameLayout;

final class TestWebViewHost {
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 900;
    private static final int DENSITY_DPI = 160;

    private final WebView webView;
    private final Presentation presentation;
    private final VirtualDisplay virtualDisplay;
    private final Surface surface;
    private final SurfaceTexture texture;

    private TestWebViewHost(WebView webView, Presentation presentation, VirtualDisplay virtualDisplay,
                            Surface surface, SurfaceTexture texture) {
        this.webView = webView;
        this.presentation = presentation;
        this.virtualDisplay = virtualDisplay;
        this.surface = surface;
        this.texture = texture;
    }

    static TestWebViewHost create(Context context) {
        SurfaceTexture texture = null;
        Surface surface = null;
        VirtualDisplay display = null;
        Presentation presentation = null;
        try {
            texture = new SurfaceTexture(false);
            texture.setDefaultBufferSize(WIDTH, HEIGHT);
            surface = new Surface(texture);
            DisplayManager manager = context.getSystemService(DisplayManager.class);
            display = manager.createVirtualDisplay(
                    "GPTWebCaptureTest",
                    WIDTH,
                    HEIGHT,
                    DENSITY_DPI,
                    surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION);
            if (display == null || display.getDisplay() == null) throw new IllegalStateException("virtual display unavailable");
            presentation = new Presentation(context, display.getDisplay(), android.R.style.Theme_DeviceDefault_NoActionBar);
            FrameLayout root = new FrameLayout(presentation.getContext());
            WebView web = new WebView(presentation.getContext());
            web.setFocusable(true);
            web.setFocusableInTouchMode(true);
            root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            presentation.setContentView(root);
            presentation.show();
            Window window = presentation.getWindow();
            if (window != null) window.setLayout(WIDTH, HEIGHT);
            web.requestFocus();
            return new TestWebViewHost(web, presentation, display, surface, texture);
        } catch (Throwable error) {
            if (presentation != null) try { presentation.dismiss(); } catch (Throwable ignored) {}
            if (display != null) try { display.release(); } catch (Throwable ignored) {}
            if (surface != null) try { surface.release(); } catch (Throwable ignored) {}
            if (texture != null) try { texture.release(); } catch (Throwable ignored) {}
            WebView fallback = new WebView(context);
            fallback.setFocusable(true);
            fallback.setFocusableInTouchMode(true);
            fallback.requestFocus();
            return new TestWebViewHost(fallback, null, null, null, null);
        }
    }

    WebView webView() { return webView; }

    void destroy() {
        try {
            webView.setWebViewClient(null);
            webView.setWebChromeClient(null);
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.clearHistory();
            webView.removeAllViews();
            webView.destroy();
        } catch (Throwable ignored) {}
        if (presentation != null) try { presentation.dismiss(); } catch (Throwable ignored) {}
        if (virtualDisplay != null) try { virtualDisplay.release(); } catch (Throwable ignored) {}
        if (surface != null) try { surface.release(); } catch (Throwable ignored) {}
        if (texture != null) try { texture.release(); } catch (Throwable ignored) {}
    }
}
