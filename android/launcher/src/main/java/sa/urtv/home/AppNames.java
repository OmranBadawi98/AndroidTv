package sa.urtv.home;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;

import java.util.HashMap;
import java.util.Map;

/**
 * The name shown for an app: the one the owner's agent gave it on this TV (optimize/dock.sh name), else its own, the
 * app's real name (the owner wants YouTube, not «يوتيوب»; res/values/app_names.xml is no longer used for the label).
 */
final class AppNames {
    static final int MAX_LEN = 30;

    private AppNames() {}

    static String label(Context c, ResolveInfo ri) {
        return label(c, ri, given(c));
    }

    static String label(Context c, ResolveInfo ri, Map<String, String> given) {
        String set = given.get(ri.activityInfo.packageName);
        if (set != null) return set;
        return String.valueOf(ri.loadLabel(c.getPackageManager()));
    }

    /** The same for any installed app, one with no launcher entry or a disabled one too («إدارة التطبيقات»). */
    static String label(Context c, ApplicationInfo ai, Map<String, String> given) {
        String set = given.get(ai.packageName);
        if (set != null) return set;
        return String.valueOf(c.getPackageManager().getApplicationLabel(ai));
    }

    /** Where the shown name comes from, for dock.sh names: agent or own. */
    static String source(Context c, ResolveInfo ri, Map<String, String> given) {
        return given.containsKey(ri.activityInfo.packageName) ? "agent" : "own";
    }

    /** Names set on this TV, kept as "pkg|name" lines in the home screen's preferences. */
    static Map<String, String> given(Context c) {
        Map<String, String> m = new HashMap<>();
        for (String row : Dock.prefs(c).getString("names", "").split("\n")) {
            int bar = row.indexOf('|');
            if (bar > 0) m.put(row.substring(0, bar), row.substring(bar + 1));
        }
        return m;
    }

    static void save(Context c, Map<String, String> m) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) b.append(e.getKey()).append('|').append(e.getValue()).append('\n');
        Dock.prefs(c).edit().putString("names", b.toString()).apply();
    }

    /** A name the agent may set: Arabic letters, one line, at most MAX_LEN characters, no separators. */
    static boolean valid(String name) {
        return name.length() <= MAX_LEN && hasArabic(name) && name.indexOf('|') < 0 && name.indexOf(';') < 0
            && name.indexOf('\n') < 0;
    }

    static boolean hasArabic(String s) {
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if ((ch >= 0x0600 && ch <= 0x06FF) || (ch >= 0x0750 && ch <= 0x077F) || (ch >= 0xFB50 && ch <= 0xFEFC)) return true;
        }
        return false;
    }
}
