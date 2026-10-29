package sa.urtv.home;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * «إدارة التطبيقات»: every app on the TV, from the remote. «الرئيسية» puts an app in the favourites row or takes it out
 * (Dock, like «ترتيب المفضلة»); «تعطيل» turns an app off and «تفعيل» back on, through the TV's own ADB (AdbSelf), asked
 * twice («تأكيد؟») and never for what Protected keeps on. Rows are built right-aligned by hand, like DockActivity.
 * Not exported: only the home screen opens it (its tiles' menu), so no other app can reach the TV's ADB through it.
 */
public class AppsActivity extends Activity {
    private static final int GOLD = 0xFFD6AF6B, INK = 0xFF04120C, TEXT = 0xFFF6EFE0, DIM = 0xFFC6BBA4;

    /** one installed app as this screen shows it */
    private static final class App {
        ApplicationInfo info;
        String name;
        boolean enabled, opens, kept;
    }

    private Protected kept;
    private Map<String, String> given, dockable;
    private final List<String> row = new ArrayList<>();
    private final List<App> all = new ArrayList<>();
    private final Map<String, View> rowOf = new HashMap<>();
    private LinearLayout list;
    private TextView status;
    private View screen;
    private boolean verified, showSystem, busy;
    private String confirming;   // the app whose «تعطيل» was pressed once

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Digits.init(this);
        kept = new Protected(this);
        given = AppNames.given(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0xFF0A2016, 0xFF04120C, 0xFF020806}));
        scroll.setVerticalScrollBarEnabled(false);
        FrameLayout frame = new FrameLayout(this);
        scroll.addView(frame);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(36), 0, dp(48));
        frame.addView(col, new FrameLayout.LayoutParams(dp(720), -2, Gravity.CENTER_HORIZONTAL));

        col.addView(text("إدارة التطبيقات", 26, "Medium", TEXT), right(0));
        col.addView(text("«الرئيسية» يضيف التطبيق إلى المفضلة أو يشيله. «تعطيل» يوقف التطبيق كليًا ويرجع بـ«تفعيل». "
            + "المحمي (البث، الصوت، الريموت، المصادر، التحديثات، المتجر، الشاشة الرئيسية) ما يتعطل.", 14, "Regular", DIM), right(dp(6)));
        status = text("أول تعطيل: يسألك التلفزيون «Allow USB debugging?»، اختر «السماح دائمًا» ثم «سماح».", 13, "Medium", GOLD);
        col.addView(status, right(dp(24)));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        col.addView(list, new LinearLayout.LayoutParams(-1, -2));
        setContentView(scroll);
        screen = scroll;
        load();
        render(null);
    }

    /** Like «الرقابة الأبوية»: with a PIN set, this screen always asks for it, whoever opened it. */
    @Override
    protected void onResume() {
        super.onResume();
        if (verified) return;
        String[] targets = ParentalPolicy.targets(ParentalPolicy.PARENTAL_SCREEN, null);
        if (!Parental.needsPin(this, targets)) {
            verified = true;
            return;
        }
        screen.setVisibility(View.INVISIBLE);
        Parental.guard(this, Parental.adhanFirst(this), "إدارة التطبيقات", targets, () -> {
            verified = true;
            screen.setVisibility(View.VISIBLE);
        }, () -> finish());
    }

    @Override
    protected void onPause() {
        super.onPause();
        PinPrompt.dismiss(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        verified = false;
    }

    // ---------------------------------------------------------------- the apps

    private void load() {
        PackageManager pm = getPackageManager();
        dockable = Dock.launchable(this);
        row.clear();
        row.addAll(Dock.shown(this, dockable));
        all.clear();
        for (ApplicationInfo ai : pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS
                | PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS)) {
            App a = new App();
            a.info = ai;
            a.name = AppNames.label(this, ai, given);
            a.enabled = ai.enabled;
            a.opens = pm.getLeanbackLaunchIntentForPackage(ai.packageName) != null || pm.getLaunchIntentForPackage(ai.packageName) != null
                || (ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
            a.kept = kept.has(ai);
            all.add(a);
        }
        Collections.sort(all, (x, y) -> x.name.compareToIgnoreCase(y.name));
    }

    private void render(String focusPkg) {
        list.removeAllViews();
        rowOf.clear();
        List<App> off = new ArrayList<>(), apps = new ArrayList<>(), system = new ArrayList<>();
        for (App a : all) (!a.enabled ? off : a.opens ? apps : system).add(a);

        if (!off.isEmpty()) {
            heading("المعطّلة (" + ar(off.size()) + ")");
            for (App a : off) list.addView(appRow(a), rowParams());
        }
        heading("التطبيقات (" + ar(apps.size()) + ")");
        for (App a : apps) list.addView(appRow(a), rowParams());

        View more = card(null, showSystem ? "إخفاء تطبيقات النظام" : "إظهار تطبيقات النظام (" + ar(system.size()) + ")", null, v -> {
            showSystem = !showSystem;
            render(null);
        });
        LinearLayout.LayoutParams mp = rowParams();
        mp.topMargin = dp(22);
        list.addView(more, mp);
        if (showSystem) {
            heading("تطبيقات النظام");
            for (App a : system) list.addView(appRow(a), rowParams());
        }
        View arrange = card(null, "ترتيب المفضلة…", null, v -> startActivity(new Intent(this, DockActivity.class)));
        LinearLayout.LayoutParams ap = rowParams();
        ap.topMargin = dp(22);
        list.addView(arrange, ap);

        View f = focusPkg != null && rowOf.containsKey(focusPkg) ? rowOf.get(focusPkg) : list.getChildAt(1);
        if (f == null) f = more;
        final View target = f;
        target.post(target::requestFocus);
    }

    private void heading(String s) {
        TextView h = text(s, 13, "Medium", GOLD);
        LinearLayout.LayoutParams hp = right(0);
        hp.topMargin = dp(22);
        hp.bottomMargin = dp(8);
        list.addView(h, hp);
    }

    /** [تعطيل] [الرئيسية] [  name / state  icon ] — right-aligned by hand. */
    private View appRow(final App a) {
        String pkg = a.info.packageName;
        boolean inRow = row.contains(pkg);
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);

        View power;
        if (!a.enabled && !kept.homeScreen(pkg)) {   // a protected app that is off may come back on: keep.sh does the same
            power = pill("تفعيل", false, v -> change(a, true));
        } else if (a.kept) {
            power = pill("محمي", false, v -> toast("هذا التطبيق يبقى كما هو: التلفزيون يحتاجه"));
        } else {
            power = pill(pkg.equals(confirming) ? "تأكيد؟" : "تعطيل", pkg.equals(confirming), v -> {
                if (pkg.equals(confirming)) change(a, false);
                else { confirming = pkg; render(pkg); }
            });
        }
        r.addView(power, new LinearLayout.LayoutParams(dp(92), -1));

        View home = null;
        if (a.enabled && dockable.containsKey(pkg)) {
            home = pill(inRow ? "✓ الرئيسية" : "+ الرئيسية", inRow, v -> toggleRow(pkg));
        }
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(110), -1);
        hp.leftMargin = dp(8);
        r.addView(home != null ? home : new View(this), hp);

        String state = !a.enabled ? (a.kept ? "معطّل · محمي" : "معطّل") : a.kept ? "محمي" : inRow ? "في الرئيسية" : "";
        View c = card(pkg, a.name, state, v -> {
            confirming = null;
            toast(pkg);
        });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1f);
        cp.leftMargin = dp(10);
        r.addView(c, cp);
        rowOf.put(pkg, power);
        return r;
    }

    // ---------------------------------------------------------------- actions

    private void toggleRow(String pkg) {
        if (row.contains(pkg)) {
            if (row.size() <= Dock.MIN) { toast("المفضلة لا تقل عن " + ar(Dock.MIN) + " تطبيقات"); return; }
            row.remove(pkg);
        } else {
            if (row.size() >= Dock.MAX) { toast("المفضلة ممتلئة (" + ar(Dock.MAX) + ")"); return; }
            row.add(pkg);
        }
        Dock.save(this, row);
        render(pkg);
    }

    /** pm disable-user / pm enable through the TV's ADB, off the main thread; one at a time. */
    private void change(final App a, final boolean enable) {
        final String pkg = a.info.packageName;
        confirming = null;
        if (busy) return;
        if (enable ? kept.homeScreen(pkg) : kept.has(a.info)) { toast("هذا التطبيق يبقى كما هو"); return; }   // checked again: never trust the button
        if (!pkg.matches("[A-Za-z0-9_.]+")) return;   // it goes into a shell command
        busy = true;
        status.setText((enable ? "جارٍ تفعيل " : "جارٍ تعطيل ") + a.name + "… إذا سألك التلفزيون «Allow USB debugging?» اختر «السماح دائمًا».");
        new Thread(() -> {
            String msg;
            try {
                String out = AdbSelf.shell((enable ? "pm enable " : "pm disable-user --user 0 ") + pkg, 60000);
                boolean ok = enable ? out.contains("new state: enabled") : out.contains("new state: disabled");
                msg = ok ? (enable ? "تم تفعيل " : "تم تعطيل ") + a.name : "ما تم: " + out.trim();
            } catch (AdbSelf.Failure e) {
                msg = e.getMessage();
            } catch (Exception e) {
                msg = "انقطع الاتصال بالتلفزيون: أعد المحاولة";
            }
            final String m = msg;
            runOnUiThread(() -> {
                busy = false;
                if (isFinishing()) return;
                status.setText(m);
                load();
                render(pkg);
            });
        }).start();
    }

    // ---------------------------------------------------------------- views

    /** A row card: icon on the right, then the name and its state; pkg null = a text button. */
    private View card(String pkg, String label, String state, View.OnClickListener click) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        c.setPadding(dp(16), dp(10), dp(16), dp(10));
        c.setFocusable(true);
        c.setClickable(true);
        c.setId(View.generateViewId());
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        c.setBackground(bg);

        TextView st = text(state == null ? "" : state, 13, "Regular", DIM);
        c.addView(st, new LinearLayout.LayoutParams(-2, -2));
        c.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        final TextView name = text(label, 17, "Medium", TEXT);
        name.setSingleLine(true);
        c.addView(name, new LinearLayout.LayoutParams(-2, -2));
        if (pkg != null) {
            ImageView ic = new ImageView(this);
            try {
                Drawable d = getPackageManager().getApplicationIcon(pkg);
                ic.setImageDrawable(d);
            } catch (Exception ignored) { }
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(30), dp(30));
            ip.leftMargin = dp(12);
            c.addView(ic, ip);
        }
        View.OnFocusChangeListener paint = (v, has) -> {
            bg.setColor(has ? 0x33D6AF6B : 0x14FFFFFF);
            bg.setStroke(dp(2), has ? GOLD : 0x1FD6AF6B);
        };
        c.setOnFocusChangeListener(paint);
        paint.onFocusChange(c, false);
        c.setOnClickListener(click);
        return c;
    }

    /** lit: gold even without focus (in the row, or waiting for «تأكيد؟») */
    private View pill(String label, final boolean lit, View.OnClickListener click) {
        final TextView p = text(label, 13, "Medium", DIM);
        p.setGravity(Gravity.CENTER);
        p.setFocusable(true);
        p.setClickable(true);
        p.setId(View.generateViewId());
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        p.setBackground(bg);
        View.OnFocusChangeListener paint = (v, has) -> {
            bg.setColor(has ? 0x33EE8B70 : lit ? GOLD : 0x0DFFFFFF);
            bg.setStroke(dp(2), has ? 0xFFEE8B70 : lit ? GOLD : 0x14FFFFFF);
            p.setTextColor(has ? 0xFFFFD9CF : lit ? INK : DIM);
        };
        p.setOnFocusChangeListener(paint);
        paint.onFocusChange(p, false);
        p.setOnClickListener(click);
        return p;
    }

    // ---------------------------------------------------------------- helpers

    private TextView text(String s, float sp, String weight, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.RIGHT);
        t.setTypeface(Fonts.get(this, "Thmanyah-" + weight + ".otf"));
        return t;
    }

    private LinearLayout.LayoutParams right(int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = bottom;
        return p;
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(8);
        return p;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private static String ar(int n) {
        return Digits.of(String.valueOf(n));
    }
}
