package sa.urtv.home;

import android.content.Context;
import android.graphics.Typeface;

import java.util.HashMap;
import java.util.Map;

/**
 * A font from assets/fonts (Thmanyah, which comes from a private repo in CI), or, in a build without it, the nearest weight of
 * IBM Plex Sans Arabic (assets/plex, OFL). Never throws: a screen must not crash for a missing font (tilawah did).
 */
public final class Fonts {
    private static final Map<String, Typeface> CACHE = new HashMap<>();

    private Fonts() {}

    /** file: "Thmanyah-Medium.otf", "ThmanyahDisplay-Bold.otf"… */
    public static synchronized Typeface get(Context c, String file) {
        Typeface tf = CACHE.get(file);
        if (tf != null) return tf;
        try {
            tf = Typeface.createFromAsset(c.getAssets(), "fonts/" + file);
        } catch (RuntimeException e) {
            String plex = file.contains("Display") || file.contains("Light") ? "Light"
                : file.contains("Medium") || file.contains("Bold") ? "Medium" : "Regular";
            try { tf = Typeface.createFromAsset(c.getAssets(), "plex/IBMPlexSansArabic-" + plex + ".ttf"); }
            catch (RuntimeException e2) { tf = Typeface.create("sans-serif", Typeface.NORMAL); }
        }
        CACHE.put(file, tf);
        return tf;
    }
}
