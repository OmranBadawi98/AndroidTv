package sa.urtv.home;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What «إدارة التطبيقات» never disables: optimize/lists/keep.txt (AirPlay, Cast, voice, the remote, inputs, updates, the
 * Play Store, the stock home and the setup app…) and core.txt (Android's own core: without some of these the TV stops at its
 * boot screen), the same lists optimize/disable.sh enforces, copied in at build time; and, read on the TV itself, our two
 * apps, every home screen, the keyboard in use and persistent system apps. Disabling a home screen is the risky switch only
 * optimize/install.sh launcher makes, behind its safety switch.
 */
final class Protected {
    private final Set<String> names = new HashSet<>(), homes = new HashSet<>();
    private final List<Pattern> core = new ArrayList<>();

    Protected(Context c) {
        for (String line : lines(c, "lists/keep.txt")) names.add(line.split("\\s")[0]);
        for (String line : lines(c, "lists/core.txt")) core.add(Pattern.compile(line.split("\\s")[0]));
        names.add(c.getPackageName());
        names.add("sa.urtv.screensaver");
        List<ResolveInfo> found = c.getPackageManager().queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS);
        for (ResolveInfo ri : found) homes.add(ri.activityInfo.packageName);
        // keep.txt's «Home screens» and home-replaced.txt: what optimize/install.sh launcher turns off for ours, behind its safety switch
        homes.addAll(java.util.Arrays.asList("com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher",
                "com.google.android.tungsten.setupwraith", "com.tcl.keycustomfunctionservice"));
        for (String line : lines(c, "lists/home-replaced.txt")) homes.add(line.split("\\s")[0]);
        names.addAll(homes);
        String ime = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        if (ime != null && ime.contains("/")) names.add(ime.substring(0, ime.indexOf('/')));
    }

    /** Whether this app must stay on. A list that could not be read protects everything: better nothing than a broken TV. */
    boolean has(ApplicationInfo ai) {
        if (names.isEmpty() || core.isEmpty()) return true;
        if ((ai.flags & ApplicationInfo.FLAG_PERSISTENT) != 0) return true;
        if (names.contains(ai.packageName)) return true;
        for (Pattern p : core) if (p.matcher(ai.packageName).matches()) return true;
        return false;
    }

    /** A home screen or its helper: not even turned back on from here, that would take Home from ours (optimize/restore-home.sh does it). */
    boolean homeScreen(String pkg) {
        return homes.contains(pkg);
    }

    /** A list's package lines: no comments, no blank lines. Empty if the asset is missing. */
    private static List<String> lines(Context c, String asset) {
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getAssets().open(asset), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) out.add(line);
            }
        } catch (java.io.IOException ignored) { }
        return out;
    }
}
