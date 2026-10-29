package sa.urtv.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * «ذهب 24   488.00 درهم», a card at the home screen's top-left corner: the 24K price per gram in dirhams that Dubai City of Gold publishes on
 * its page (the screensaver's markets line shows the same). Asked at most once a minute with the clock; the last answer is kept,
 * shown dimmed past an hour, and not at all past a day.
 */
final class GoldLine {
    static final String URL = "https://dubaicityofgold.com/";
    static final long REFRESH_MS = 60 * 1000L, OLD_MS = 60 * 60 * 1000L, GONE_MS = 24 * 60 * 60 * 1000L;
    static final Pattern PRICE = Pattern.compile("24K\\s*Gold\\s*</span>\\s*<span[^>]*>\\s*AED\\s*([0-9][0-9,]*\\.?[0-9]*)", Pattern.CASE_INSENSITIVE);

    private final Context context;
    private final TextView line;
    private final android.graphics.Typeface bold;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean fetching;

    GoldLine(Context context, TextView line, android.graphics.Typeface bold) {
        this.context = context;
        this.line = line;
        this.bold = bold;
    }

    /** Called with the clock (every minute, and on resume). */
    void update(boolean latin) {
        SharedPreferences sp = prefs();
        long at = sp.getLong("at", 0), age = System.currentTimeMillis() - at;
        String price = sp.getString("price", "");
        if (price.isEmpty() || age > GONE_MS) {
            line.setVisibility(android.view.View.GONE);
        } else {
            line.setText(card(DigitsText.convert(price, latin), DigitsText.convert("ذهب 24", latin)));
            line.setAlpha(age > OLD_MS ? 0.55f : 1f);
            line.setVisibility(android.view.View.VISIBLE);
        }
        if (fetching || age < REFRESH_MS) return;
        fetching = true;
        new Thread(() -> {
            String got = null;
            try { got = fetch(); } catch (Exception ignored) { }
            final String p = got;
            main.post(() -> {
                fetching = false;
                if (p != null) {
                    prefs().edit().putString("price", p).putLong("at", System.currentTimeMillis()).apply();
                    update(latin);
                }
            });
        }, "gold-price").start();
    }

    /** «ذهب 24» small in gold, the price large in white, «درهم» small: one line, read from the right. */
    private CharSequence card(String price, String label) {
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder();
        int at = b.length();
        b.append(label);
        b.setSpan(new android.text.style.ForegroundColorSpan(0xFFD6AF6B), at, b.length(), 0);
        b.setSpan(new android.text.style.RelativeSizeSpan(0.82f), at, b.length(), 0);
        b.append("   ");
        at = b.length();
        b.append("\u2066").append(price).append("\u2069");   // the number stays whole in a right-to-left line
        b.setSpan(new android.text.style.RelativeSizeSpan(1.25f), at, b.length(), 0);
        if (bold != null && android.os.Build.VERSION.SDK_INT >= 28) b.setSpan(new android.text.style.TypefaceSpan(bold), at, b.length(), 0);
        b.append(" ");
        at = b.length();
        b.append("درهم");
        b.setSpan(new android.text.style.ForegroundColorSpan(0xCCFFFFFF), at, b.length(), 0);
        b.setSpan(new android.text.style.RelativeSizeSpan(0.82f), at, b.length(), 0);
        return b;
    }

    /** "488.00", or null when the page has no 24K price. */
    static String parse(String html) {
        Matcher m = PRICE.matcher(html);
        if (!m.find()) return null;
        try {
            double p = Double.parseDouble(m.group(1).replace(",", ""));
            return p > 0 && p < 100000 ? String.format(java.util.Locale.US, "%.2f", p) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String fetch() throws java.io.IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(URL).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV)");
        try {
            if (c.getResponseCode() != 200) return null;
            try (java.io.InputStream in = c.getInputStream()) {
                return parse(new String(Bounded.readAll(in, Bounded.JSON_MAX), "UTF-8"));
            }
        } finally {
            c.disconnect();
        }
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences("gold", Context.MODE_PRIVATE);
    }
}
