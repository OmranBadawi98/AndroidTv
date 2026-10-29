package sa.urtv.screensaver;

import android.content.Context;

import java.io.File;

/**
 * The page and the version come only with the APK on this TV: nothing is pulled from GitHub, so a screen change reaches
 * the TV with a new build of the app. The methods stay so the settings, SaverReceiver's UPDATE and Prefs.pageBase keep working.
 */
final class Updater {
    static final long INTERVAL_MS = 6L * 3600 * 1000;   // UpdateJob's wake-up period

    static final String OFF = "التحديث من الإنترنت متوقف: الصفحة تأتي مع التطبيق";

    private Updater() {}

    /** where an older build kept a downloaded page; nothing writes it now, and Prefs.pageBase uses it only if it is there */
    static File pageFile(Context c) { return new File(c.getFilesDir(), "web/tv.html"); }

    /** the build stamp of the page the app carries ("" if it cannot be read) */
    static String assetStamp(Context c) {
        try { return PageStamp.read(c.getAssets().open("tv.html")); } catch (Exception e) { return ""; }
    }

    /** the build stamp of a downloaded page file */
    static String fileStamp(File f) {
        try { return PageStamp.read(new java.io.FileInputStream(f)); } catch (Exception e) { return ""; }
    }

    /** Was the 6-hourly page check; there is nothing to check now. */
    static void checkIfDue(Context c) { }

    /** The settings' «تحديث الشاشة الآن» and SaverReceiver's UPDATE: no network, only the line that says so. */
    static String check(Context c, boolean force) {
        pageFile(c).delete();   // a page an older build downloaded is not used again
        return OFF;
    }

    private static String arabic(String v) {
        StringBuilder b = new StringBuilder();
        for (char ch : v.toCharArray()) {
            if (ch >= '0' && ch <= '9') b.append(DigitsText.convert(String.valueOf(ch), false));
            else if (ch == '.') b.append('٫');
            else b.append(ch);
        }
        return b.toString();
    }

    /** The lines under the settings: this app's version and how it is updated. */
    static String[] versionLines(Context c) {
        return new String[] {
            "الإصدار الحالي: " + arabic(BuildConfig.VERSION_NAME),
            "طريقة التحديث: ثبّت نسخة جديدة من التطبيق بنفسك، وإعداداتك كلها تبقى.",
        };
    }
}
