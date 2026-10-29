package sa.urtv.screensaver;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.annotation.TargetApi;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** The WebView shared by the preview activity and the dream. */
final class ScreenView {
    private ScreenView() {}

    /** Told when the page's renderer process died: the view is dead and must be thrown away (release), never reloaded. */
    interface Gone { void onGone(WebView dead); }

    /** start: the dream's own start (it counts toward the first-run note's three); false for the preview and a renderer's re-creation */
    static WebView create(final Context c, Gone gone, boolean start) { return build(c, new Prefs(c).screenUrl(c, start), true, gone); }

    /** For the settings' pickers: the page drawn live for one card (url from Prefs.previewUrl), nothing else started. */
    static WebView createPreview(final Context c, String url, Gone gone) { return build(c, url, false, gone); }

    @SuppressLint("SetJavaScriptEnabled")
    @SuppressWarnings("deprecation")
    private static WebView build(final Context c, String url, boolean screen, final Gone gone) {
        WebView w = new WebView(c);
        w.setBackgroundColor(screen ? 0xFF040E09 : 0);   // no white flash before the page paints (a preview is the card on the picker's own background)
        w.setFocusable(false);
        w.setVerticalScrollBarEnabled(false);
        w.setHorizontalScrollBarEnabled(false);
        w.setOverScrollMode(View.OVER_SCROLL_NEVER);
        final Context app = c.getApplicationContext();   // the client must not keep the dream or activity alive
        w.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                WebResourceResponse res = PhotoCache.serve(app, r.getUrl().toString());
                return res != null ? res : super.shouldInterceptRequest(v, r);
            }

            /** The renderer died or was killed for memory. Without this the whole app goes down with it; with it the host decides (Gone). */
            @TargetApi(26)
            @Override
            public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail detail) {
                if (gone != null) gone.onGone(v);
                else release(v);
                return true;
            }
        });
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        // lets the page call Yahoo and other APIs directly, without a CORS gateway
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);   // photos are cached by PhotoCache, data must be fresh
        // The page sizes its text to the screen; the TV's font size setting (Android scales WebView text by it) made
        // it larger than its boxes, so the left column spilled off screen and moved as the clock and the matches changed.
        s.setTextZoom(100);
        if (android.os.Build.VERSION.SDK_INT >= 23 && screen) s.setOffscreenPreRaster(true);   // draw before it is shown
        if (screen) w.addJavascriptInterface(new NowPlayingBridge(c), "UrtvNative");   // the «يُتلى الآن» card
        w.loadUrl(url);
        if (screen) UpdateJob.schedule(c);
        return w;
    }

    /** Takes the view out of its window and frees the WebView and everything it holds. */
    static void release(WebView w) {
        if (w == null) return;
        w.stopLoading();
        if (w.getParent() instanceof ViewGroup) ((ViewGroup) w.getParent()).removeView(w);
        w.destroy();
    }
}
