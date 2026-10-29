package sa.urtv.screensaver;

import android.content.Context;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * Answers the page's font and photo requests from the device, so the screensaver opens at once and nothing is fetched
 * from GitHub: fonts and bundled photos come from the APK, the owner's own photos from Own's folder.
 */
final class PhotoCache {
    static final String REPO = Channel.BASE;
    private static final String BUNDLED = REPO + "assets/backgrounds/", FONTS = REPO + "assets/fonts/", PLEX = REPO + "assets/plex/";
    /** the owner's own photos (Own): not a real address, the page asks for N.jpg here and only this app answers */
    static final String OWN = "https://own.urtv.invalid/";

    private PhotoCache() {}

    /** null lets the WebView load the URL itself; anything else under REPO is a 404 from here, never the network */
    static WebResourceResponse serve(Context c, String url) {
        try {
            if (url.startsWith(FONTS)) {
                String name = url.substring(FONTS.length());
                if (!name.matches("[A-Za-z0-9_-]+\\.woff2")) return missing();
                // fonts are fetched with CORS, and the page's origin is file://
                java.util.Map<String, String> h = new java.util.HashMap<>();
                h.put("Access-Control-Allow-Origin", "*");
                return new WebResourceResponse("font/woff2", null, 200, "OK", h, c.getAssets().open("fonts/" + name));
            }
            if (url.startsWith(PLEX)) {   // IBM Plex Sans Arabic (OFL): the page's font when the build has no Thmanyah
                String name = url.substring(PLEX.length());
                if (!name.matches("IBMPlexSansArabic-[A-Za-z]+\\.ttf")) return missing();
                java.util.Map<String, String> h = new java.util.HashMap<>();
                h.put("Access-Control-Allow-Origin", "*");
                return new WebResourceResponse("font/ttf", null, 200, "OK", h, c.getAssets().open("plex/" + name));
            }
            if (url.startsWith(OWN)) {
                String name = url.substring(OWN.length());
                if (!name.matches("([1-9]|10)\\.jpg")) return null;
                File f = Own.file(c, Integer.parseInt(name.substring(0, name.indexOf('.'))));
                return f.isFile() ? jpeg(new FileInputStream(f)) : null;
            }
            if (url.startsWith(BUNDLED)) {
                String name = safeName(url.substring(BUNDLED.length()));
                return name == null ? missing() : jpeg(c.getAssets().open("backgrounds/" + name));
            }
        } catch (Exception ignored) { }
        return url.startsWith(REPO) ? missing() : null;   // e.g. a Thmanyah font this build does not carry
    }

    private static String safeName(String s) {
        return s.matches("[A-Za-z0-9_-]+\\.jpg") ? s : null;
    }

    private static WebResourceResponse jpeg(InputStream in) {
        return new WebResourceResponse("image/jpeg", null, in);
    }

    private static WebResourceResponse missing() {
        return new WebResourceResponse("text/plain", null, 404, "Not Found", null, new ByteArrayInputStream(new byte[0]));
    }
}
