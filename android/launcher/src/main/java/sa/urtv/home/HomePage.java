package sa.urtv.home;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.Collections;

/**
 * The home screen's face: the screensaver's own page (#homebg=1: clock, date, weather, markets with the gold, the card, the
 * prayers and the bottom row) over the launcher's photo, so the home screen and the screensaver look the same. The top bar and
 * the apps are the launcher's own views over and under it; the page is never focused. Settings come from the screensaver app's
 * ConfigProvider (same signing key), like HomeCard's; this replaces the cards while it is on.
 */
final class HomePage {
    private static final Uri CONFIG = Uri.parse("content://sa.urtv.screensaver.config");
    private static final String PREFS = "homepage";   // the last settings read, for when the screensaver does not answer
    static final String REPO = "https://urtv.invalid/";   // src/build.py's REPO: answered here, never fetched
    private static final String FONTS = REPO + "assets/fonts/", PLEX = REPO + "assets/plex/";

    private final Activity activity;
    private final ViewGroup parent;
    private final int index;   // where in parent the page goes: over the photo, under the scroll
    private WebView web;
    private String url;
    private final RenderGuard guard = new RenderGuard();
    private boolean resumed;

    HomePage(Activity activity, ViewGroup parent, int index) {
        this.activity = activity;
        this.parent = parent;
        this.index = index;
    }

    /** onResume: a changed setting (the city, the card…) gets a fresh page. */
    void refresh() {
        resumed = true;
        String want = readUrl();
        if (!want.equals(url)) {
            destroy();
            if (!guard.mayCreate(System.currentTimeMillis())) return;   // its renderer keeps dying: the plain home for now
            create(want);
        }
        web.onResume();
        web.resumeTimers();
    }

    /** onPause: no timers or network while an app is in front (pauseTimers is process-wide: this is the only WebView then). */
    void pause() {
        resumed = false;
        if (web != null) { web.onPause(); web.pauseTimers(); }
    }

    void destroy() {
        if (web == null) return;
        parent.removeView(web);
        web.destroy();
        web = null;
        url = null;
    }

    /** Whether the page is on screen (else the launcher shows its own clock). */
    boolean shown() { return web != null; }

    private String readUrl() {
        String frag = null;
        try (Cursor c = activity.getContentResolver().query(CONFIG, null, null, null, null)) {
            if (c != null && c.moveToFirst()) frag = c.getString(0);
        } catch (RuntimeException e) {
            // the screensaver's settings are out of reach
        }
        SharedPreferences sp = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (frag != null) sp.edit().putString("frag", frag).apply();
        else frag = sp.getString("frag", "");
        frag = frag.replaceAll("(^|&)(widget|live|home_panel|citynote|homebg)=[^&]*", "");
        return HomeCardChoice.BASE + frag + "&homebg=1";
    }

    @SuppressLint("SetJavaScriptEnabled")
    @SuppressWarnings("deprecation")
    private void create(String u) {
        web = new WebView(activity);
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setFocusable(false);
        web.setFocusableInTouchMode(false);
        web.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail detail) {
                if (v != web) { v.destroy(); return true; }
                guard.gone(System.currentTimeMillis());
                destroy();
                if (resumed) refresh();
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                WebResourceResponse font = font(activity, r.getUrl().toString());
                return font != null ? font : super.shouldInterceptRequest(v, r);
            }
        });
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);   // the page calls its data APIs directly from file://, as in the screensaver
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setTextZoom(100);   // the page sizes its text to the screen; the TV's font size setting must not scale it
        parent.addView(web, index, new ViewGroup.LayoutParams(-1, -1));
        url = u;
        web.loadUrl(u);
    }

    /** The page's fonts from the APK: Thmanyah's woff2 when the build has them, IBM Plex Sans Arabic's ttf (OFL) else; null for anything else. */
    static WebResourceResponse font(Context c, String s) {
        String dir, name, type;
        if (s.startsWith(FONTS)) { dir = "fonts/"; name = s.substring(FONTS.length()); type = "font/woff2"; if (!name.matches("[A-Za-z0-9_-]+\\.woff2")) return null; }
        else if (s.startsWith(PLEX)) { dir = "plex/"; name = s.substring(PLEX.length()); type = "font/ttf"; if (!name.matches("IBMPlexSansArabic-[A-Za-z]+\\.ttf")) return null; }
        else return null;
        try {
            return new WebResourceResponse(type, null, 200, "OK", Collections.singletonMap("Access-Control-Allow-Origin", "*"),
                c.getAssets().open(dir + name));
        } catch (java.io.IOException e) {
            return null;
        }
    }
}
