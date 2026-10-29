package sa.urtv.home;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.database.Cursor;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import sa.urtv.home.tilawah.NowPlaying;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Apple TV style home screen: clock top-left, pills top-right, the focused dock app's name over an
 * ambient glow, a dock of pinned apps, and every other app in a grid that scrolls up on Down.
 * Plain framework views only (no AndroidX). Network only for hosted wallpapers and the optional card (HomeCard).
 *
 * Adapted from github.com/mihirgupta0900/tv-home (MIT, see android/launcher/LICENSE-tv-home):
 * Arabic text and digits, Hijri date, Thmanyah fonts, the screensaver's gold palette and Saudi photos,
 * clock on the right, and a pill that starts the screensaver (a long OK opens its settings).
 */
public class MainActivity extends Activity {

    // Dock, hidden apps, Live TV and grid width come from res/values/config.xml.
    private String[] dockApps;
    private String liveTvPkg, inputPickerPkg;
    private int cols;

    static final class App {
        String pkg, label, category;   // category: the one it was found with, LEANBACK_LAUNCHER or LAUNCHER
        ComponentName component;
        Drawable art;
        boolean isBanner;
        int color;
    }

    private final Map<String, Integer> colorCache = new java.util.concurrent.ConcurrentHashMap<>();   // filled off the main thread
    // Banners and icons come from the apps at their own size (often 640x360 or more); the tiles are ~1/5 of the
    // screen wide, so they are kept at that size, and the cache holds a few MB at most (sized in KB).
    private final LruCache<String, Drawable> artCache = new LruCache<String, Drawable>(6 * 1024) {
        @Override protected int sizeOf(String k, Drawable d) {
            return d instanceof BitmapDrawable ? ((BitmapDrawable) d).getBitmap().getByteCount() / 1024 : 256;
        }
    };
    private final List<View> dockCards = new ArrayList<>();
    private final List<View> gridCards = new ArrayList<>();
    private final List<View> pills = new ArrayList<>();

    private HomeScrollView scroll;
    private LinearLayout content, dockRow;
    private FrameLayout hero;
    private LinearLayout grid;
    private View glow;
    private ImageView wallBack, wallFront;
    private View gridShade;
    private String[] wallpapers = new String[0];
    private int wallIndex = -1;
    private long wallChangedAt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable rotateWallpaper = this::nextWallpaper;
    static final long WALLPAPER_INTERVAL_MS = 10 * 60 * 1000;
    private TextView greeting, weatherLine, clock, ampm, date, goldText, title, appsLabel;
    private GoldLine goldLine;
    private HomePage homePage;   // the screensaver's page as the home screen's face (HomePage); the apps are one press down
    private Pill wifiPill;   // «واي فاي»: in the row only while «زر الواي فاي» is on in the screensaver's settings; the network's name shows while it has the focus
    private LinearLayout pillRow;
    private Greeting greetingLine;
    private int glowColor = 0xFF3A3A3A;
    private ValueAnimator glowAnim;
    private int lastFocusedId = View.NO_ID;
    private boolean firstShow = true;
    private int packageSeq;
    private PopupWindow inputsPopup;
    private HomeCard homeCard;
    private NowCard nowCard;   // «يُتلى الآن»: at the right end of the pills row while a surah or a station is on
    private TextView adhanBar;   // «أذان المغرب بعد ٨ دقائق», over the top edge; while the adhan plays «أذان المغرب · أي زر يوقف الأذان»
    private final AdhanKeys adhanKeys = new AdhanKeys();
    private View clockBlock, bottomBlock;   // the greeting, clock and date; the app title and the dock under it

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            updateClock();
            updateWifi();
            updateNowPlaying();   // the minutes left of its sleep timer
        }
    };
    // «تلاوة» started, paused, changed or stopped a surah or a station (MusicService says so; nothing polls): the «يُتلى الآن» card follows
    private final BroadcastReceiver nowPlayingChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            updateNowPlaying();
            updateAdhanBar(ConfigSnapshot.read(MainActivity.this));   // the adhan began or ended: the bar names it, or goes
        }
    };
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { buildApps(); }
    };
    // favourites changed in «ترتيب المفضلة» or from ADB while the home screen is up
    private final BroadcastReceiver dockChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { dockApps = loadDock(); buildApps(); }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Digits.init(this);   // the screensaver's «الأرقام» setting, for every number this screen writes
        dockApps = loadDock();
        liveTvPkg = getString(R.string.live_tv_package);
        inputPickerPkg = getString(R.string.input_picker_package);
        cols = getResources().getInteger(R.integer.columns);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setContentView(buildUi());
        buildApps();

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packages, pf);
        registerReceiver(dockChanged, new IntentFilter(Dock.CHANGED));
        registerReceiver(nowPlayingChanged, new IntentFilter(NowPlaying.CHANGED));
    }

    @Override
    protected void onResume() {
        super.onResume();
        Parental.SESSION.clear();   // the home screen is in front again: an app opened with the PIN asks again next time
        IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        tf.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        registerReceiver(tick, tf);
        updateClock();
        updateWifi();
        updateNowPlaying();
        homePage.refresh();   // the page instead of the cards
        wireFocus();   // the «المهام» card may have come or gone with the settings
        showPage();
        // Catch installs/removals that happened while another app was in front.
        if (getPackageManager().getChangedPackages(packageSeq) != null) buildApps();
        if (firstShow) {   // the entrance plays once the favourites are on screen (they come from the loader thread)
            firstShow = false;
            if (appsShown) playEntrance(); else entranceDue = true;
        }
        // New photo on return if the current one has been up for the full interval.
        if (wallIndex < 0 || SystemClock.elapsedRealtime() - wallChangedAt >= WALLPAPER_INTERVAL_MS) nextWallpaper();
        else {
            scheduleWallpaper(WALLPAPER_INTERVAL_MS - (SystemClock.elapsedRealtime() - wallChangedAt));
            checkOwnChanged();   // the owner added or removed photos while another app was in front
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        PinPrompt.dismiss(this);
        handler.removeCallbacks(rotateWallpaper);
        unregisterReceiver(tick);
        if (inputsPopup != null) inputsPopup.dismiss();
        homeCard.pause();
        homePage.pause();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        unregisterReceiver(dockChanged);
        unregisterReceiver(nowPlayingChanged);
        loader.shutdownNow();
        homeCard.destroy();
        homePage.destroy();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Parental.SESSION.clear();
        // Home pressed while already home: back to the page.
        if (inputsPopup != null) inputsPopup.dismiss();
        showPage();
    }

    @Override
    public void onBackPressed() {
        // Back from the apps returns to the page; Back on the page starts the screensaver, as OK on its pill does (same lock)
        if (scroll.getScrollY() > 0) showPage();
        else if (isInstalled(SAVER_PKG)) guarded(ParentalPolicy.PILL_SAVER, SAVER_PKG, "شاشة التوقف", this::startSaver);
    }

    /** The page in full: the top of the screen, the focus on the top bar (DOWN goes to the apps). */
    private void showPage() {
        if (!pills.isEmpty()) pills.get(0).requestFocus();
        scroll.smoothScrollTo(0, 0);
    }

    // ---------------------------------------------------------------- layout

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        // The home is built in code, so it inherits no direction from the window: set it here and
        // the pills, the favourites and the app rows all read from right to left.
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        // Wallpaper: two stacked layers so a new photo can crossfade in over the old one.
        wallBack = wallpaperLayer();
        wallFront = wallpaperLayer();
        root.addView(wallBack, new FrameLayout.LayoutParams(-1, -1));
        root.addView(wallFront, new FrameLayout.LayoutParams(-1, -1));
        // Scrim: darker at the top (clock, pills) and bottom (dock) so text stays readable.
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xB3000000, 0x40000000, 0x26000000, 0x8C000000, 0xE6000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        scrim.setAlpha(0.35f);   // the page brings its own veils over the photo
        gridShade = new View(this);
        gridShade.setBackgroundColor(0xE6000000);   // dark enough that the page (its big clock) stays out of the way of the apps
        gridShade.setAlpha(0f);
        root.addView(gridShade, new FrameLayout.LayoutParams(-1, -1));
        homePage = new HomePage(this, root, root.indexOfChild(gridShade));
        try {
            wallpapers = getAssets().list("wallpapers");
        } catch (java.io.IOException ignored) { }

        glow = new View(this);
        root.addView(glow, new FrameLayout.LayoutParams(-1, -1));
        setGlow(glowColor);

        scroll = new HomeScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFillViewport(true);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));

        // the slim bar before the adhan, over the home screen's top edge (the header starts 36 dp down)
        adhanBar = text(12, "sans-serif-medium", 0xFFFFE9B8);
        adhanBar.setGravity(Gravity.CENTER);
        adhanBar.setMaxLines(1);   // «إقامة العشاء بعد ١٣ دقيقة» is the longest: one line, even at 960×540
        adhanBar.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0x00DCB86F, 0x57DCB86F, 0x57DCB86F, 0x00DCB86F}));
        adhanBar.setOnFocusChangeListener((v, focused) -> {   // the bar is focusable only while the adhan plays: it lights up when OK would stop it
            v.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    focused ? new int[]{0x00DCB86F, 0xB3DCB86F, 0xB3DCB86F, 0x00DCB86F} : new int[]{0x00DCB86F, 0x57DCB86F, 0x57DCB86F, 0x00DCB86F}));
            ((TextView) v).setTextColor(focused ? 0xFFFFFFFF : 0xFFFFE9B8);
        });
        adhanBar.setVisibility(View.GONE);
        root.addView(adhanBar, new FrameLayout.LayoutParams(-1, dp(26), Gravity.TOP));

        // Hero: exactly one screen tall, header on top, title + dock at the bottom.
        hero = new FrameLayout(this);
        hero.setClipChildren(false);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        content.addView(hero, new LinearLayout.LayoutParams(-1, screenH));

        hero.addView(buildHeader(), lp(-1, -2, Gravity.TOP, dp(48), dp(36), dp(48), 0));
        // optional card under the pills, same size as beside the screensaver's clock
        homeCard = new HomeCard(this, hero, lp(dp(461), dp(250), Gravity.LEFT | Gravity.TOP, dp(48), dp(84), 0, 0), this::homeCardBox, new HomeCard.Live() {
            @Override public void focus(View target, boolean has) {   // «المهام» on the card takes the focus like a pill
                if (has) { lastFocusedId = target.getId(); scroll.smoothScrollTo(0, 0); fadeGlow(1f); }
            }
            @Override public void open() {   // OK: the list, with the focus on «🎤 تكلّم» so one more OK starts the voice adding
                launchGuarded(ParentalPolicy.CARD_TASKS, new Intent(MainActivity.this, TasksActivity.class).putExtra(TasksActivity.EXTRA_MIC, true), "المهام");
            }
        });
        hero.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> homeCard.fit());

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setClipChildren(false);
        bottom.setClipToPadding(false);
        content.addView(bottom, llp(-1, -2, dp(48), dp(28), dp(48), dp(30)));   // under the page: one press down from the top bar
        bottomBlock = bottom;

        title = text(34, "sans-serif-light", Color.WHITE);
        title.setShadowLayer(dp(18), 0, 0, 0x66000000);
        title.setSingleLine(true);
        title.setVisibility(View.GONE);   // the owner wants no name over the favourites (kept for the card's measure)
        bottom.addView(title, new LinearLayout.LayoutParams(-2, -2));
        ((LinearLayout.LayoutParams) title.getLayoutParams()).setMargins(dp(8), 0, 0, dp(14));

        dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        dockRow.setGravity(Gravity.CENTER);
        dockRow.setClipChildren(false);
        dockRow.setClipToPadding(false);
        dockRow.setPadding(dp(20), dp(22), dp(20), dp(22));
        GradientDrawable band = new GradientDrawable();
        band.setCornerRadius(dp(26));
        band.setColor(0x1FFFFFFF);
        band.setStroke(dp(1), 0x14FFFFFF);
        dockRow.setBackground(band);
        bottom.addView(dockRow, new LinearLayout.LayoutParams(-1, -2));

        appsLabel = text(11, "sans-serif-medium", 0x99FFFFFF);
        appsLabel.setText("كل التطبيقات");
        appsLabel.setTextColor(GOLD);
        content.addView(appsLabel, llp(-2, -2, dp(68), dp(8), 0, dp(18)));

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        content.addView(grid, llp(-1, -2, dp(68), 0, dp(68), dp(160)));
        return root;
    }

    /**
     * The WebView's box for that many cards, in pixels. One card is the size it always was. Two or three need room: a pair is
     * 58% of the screen wide (at 1920 that is 560 + 22 + 540), kept clear of the greeting and clock block at the right; the third
     * card goes under the pair. The box ends above the dock, or above the app title when the longest title of the favourites
     * would reach into it. Before the screen is laid out the numbers come from the screen's size alone.
     */
    private int[] homeCardBox(int cards) {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int clockLeft = 0, titleTop = 0, dockTop = 0, wide = 0;
        if (clockBlock != null && clockBlock.getWidth() > 0) clockLeft = ((View) clockBlock.getParent()).getLeft() + clockBlock.getLeft();
        if (bottomBlock != null && bottomBlock.getHeight() > 0 && dockRow != null) {
            titleTop = bottomBlock.getTop();
            dockTop = titleTop + dockRow.getTop();
            for (View c : dockCards) wide = Math.max(wide, Math.round(title.getPaint().measureText(((App) c.getTag()).label)));
        }
        return CardBox.box(cards, dm.widthPixels, dm.heightPixels, dm.density, clockLeft, titleTop, dockTop, wide);
    }

    private View buildHeader() {
        FrameLayout header = new FrameLayout(this);
        header.setClipChildren(false);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        // Top right, stacked tight: greeting, clock, date. Gravity.RIGHT, not END: for Arabic text END is the left.
        greeting = text(22, "medium", GOLD);
        greeting.setSingleLine(true);
        left.addView(greeting, llp(-2, -2, 0, 0, dp(4), 0));
        // under it: the weather of the city and the next sunrise or sunset (shown only when the greeting is on and there is an answer)
        weatherLine = text(19, "regular", 0xD9FFFFFF);
        weatherLine.setSingleLine(true);
        weatherLine.setVisibility(View.GONE);
        left.addView(weatherLine, llp(-2, -2, 0, dp(2), dp(4), 0));
        greetingLine = new Greeting(this, greeting, weatherLine);

        LinearLayout clockRow = new LinearLayout(this);
        clockRow.setGravity(Gravity.BOTTOM);
        clockRow.setGravity(Gravity.TOP);
        clock = text(96, "display", 0xFFFFFDF6);
        clock.setIncludeFontPadding(false);
        ampm = text(24, "medium", GOLD);
        // the row reads from the right: the time first, then the ص or the م on its left
        clockRow.addView(clock);
        clockRow.addView(ampm, llp(-2, -2, 0, dp(12), dp(10), 0));
        left.addView(clockRow, llp(-2, -2, 0, dp(2), 0, 0));

        date = text(22, "regular", 0xF2FFFFFF);
        date.setSingleLine(true);
        left.addView(date, llp(-2, -2, 0, dp(-10), dp(4), 0));   // the display font leaves a deep gap under its digits
        // no «بتوقيت المدينة · حساب …» line here: the owner wanted the block short (the screensaver still shows it)
        for (TextView t : new TextView[]{greeting, weatherLine, clock, ampm, date}) t.setShadowLayer(dp(6), 0, dp(2), 0x99000000);
        left.setGravity(Gravity.RIGHT);
        for (TextView t : new TextView[]{greeting, weatherLine, date}) t.setGravity(Gravity.RIGHT);
        header.addView(left, new FrameLayout.LayoutParams(-2, -2, Gravity.RIGHT | Gravity.TOP));
        left.setVisibility(View.GONE);   // the page shows the clock, the date and the weather
        clockBlock = left;

        LinearLayout right = new LinearLayout(this);
        right.setGravity(Gravity.CENTER_VERTICAL);
        right.setClipChildren(false);
        Pill saver = pill(PillText.SAVER, R.drawable.ic_screensaver);
        saver.setOnClickListener(v -> guarded(ParentalPolicy.PILL_SAVER, SAVER_PKG, "شاشة التوقف", this::startSaver));   // OK starts it, as optimize/start-saver.sh --preview does
        saver.setOnLongClickListener(v -> { guarded(ParentalPolicy.PILL_SAVER_SETTINGS, SAVER_PKG, "شاشة التوقف", () -> openPackage(SAVER_PKG)); return true; });   // a long OK: its settings, where OK used to go
        Pill inputs = pill("المصادر", R.drawable.ic_input);
        inputs.setOnClickListener(v -> showInputs(v, ParentalPolicy.PILL_INPUTS));
        Pill settings = pill("الإعدادات", R.drawable.ic_settings);
        settings.setOnClickListener(v -> launchGuarded(ParentalPolicy.PILL_TV_SETTINGS, new Intent(Settings.ACTION_SETTINGS), "إعدادات التلفزيون"));
        // the «تلاوة وأذان» pill: the Quran, the radio and the adhan are part of this app, so it opens them right here
        Pill tilawah = pill("تلاوة وأذان", R.drawable.ic_listen);
        tilawah.setOnClickListener(v -> launchGuarded(ParentalPolicy.PILL_LISTEN, new Intent(this, sa.urtv.home.tilawah.MainActivity.class), "تلاوة وأذان"));
        // «إدارة التطبيقات»: turn apps off or on, and pick the home screen's, behind the same lock as in the menu
        Pill manage = pill("إدارة التطبيقات", R.drawable.ic_apps);
        manage.setOnClickListener(v -> launchGuarded(ParentalPolicy.PARENTAL_SCREEN, new Intent(this, AppsActivity.class), "إدارة التطبيقات"));
        // «واي فاي» only when the setting is on (showWifiPill, after «المصادر»), locked with the TV's settings; no «مؤقت النوم» or «المهام»
        // pills: the owner wanted the row shorter (the sleep timer still runs from the computer)
        wifiPill = pill("واي فاي", R.drawable.ic_wifi);
        wifiPill.setOnClickListener(v -> launchGuarded(ParentalPolicy.PILL_TV_SETTINGS, new Intent(Settings.ACTION_WIFI_SETTINGS), "واي فاي"));
        List<Pill> row = new ArrayList<>(Arrays.asList(inputs, settings, manage));
        if (isInstalled(SAVER_PKG)) row.add(saver);
        if (getResources().getBoolean(R.bool.show_listening)) row.add(tilawah);
        for (Pill p : row) {
            right.addView(p, llp(-2, dp(Pill.HEIGHT), dp(10), 0, 0, 0));
            pills.add(p);
        }
        // the gold card (GoldLine): the last child, so the top-left corner; as tall as «يُتلى الآن», hung over the row the same way
        goldText = text(17, "regular", Color.WHITE);
        goldText.setSingleLine(true);
        goldText.setGravity(Gravity.CENTER_VERTICAL);
        goldText.setPadding(dp(16), 0, dp(16), 0);
        GradientDrawable goldBg = new GradientDrawable();
        goldBg.setCornerRadius(dp(NowCard.HEIGHT / 2f));
        goldBg.setColor(0x8C14110C);
        goldBg.setStroke(dp(1), 0x66D6AF6B);
        goldText.setBackground(goldBg);
        goldText.setVisibility(View.GONE);
        // not added: the page's markets line shows the gold (24K per gram in dirhams) at the top left
        // «يُتلى الآن»: the first child of the row, so it sits at its right end and the pills keep their places when it comes
        nowCard = new NowCard(this, font("medium"), font("regular"), getDrawable(R.drawable.ic_play), getDrawable(R.drawable.ic_pause));
        nowCard.setId(View.generateViewId());
        nowCard.setVisibility(View.GONE);
        nowCard.setOnClickListener(v -> NowPlaying.toggle(this));   // OK: pause or play
        nowCard.setOnLongClickListener(v -> { launchGuarded(ParentalPolicy.PILL_LISTEN, NowPlaying.player(this), "تلاوة وأذان"); return true; });   // a long OK: the player, behind the same lock as the «تلاوة وأذان» pill
        nowCard.setOnFocusChangeListener((v, has) -> {
            if (has) { lastFocusedId = v.getId(); scroll.smoothScrollTo(0, 0); fadeGlow(1f); }
        });
        // taller than a pill by 10 dp, hung 5 dp over the row's top and bottom: the row stays Pill.HEIGHT, so the pills do not move; it
        // reaches from 31 to 75 dp down, clear of the adhan bar (to 26) and the cards (from 84)
        int hang = dp((NowCard.HEIGHT - Pill.HEIGHT) / 2f);
        right.addView(nowCard, 0, llp(-2, dp(NowCard.HEIGHT), dp(10), -hang, 0, -hang));
        GradientDrawable bar = new GradientDrawable();
        bar.setCornerRadius(dp(Pill.HEIGHT / 2f + 8));
        bar.setColor(0x66101010);
        bar.setStroke(dp(1), 0x26FFFFFF);
        right.setBackground(bar);
        right.setPadding(dp(8), dp(8), dp(18), dp(8));
        header.addView(right, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.TOP));
        pillRow = right;
        return header;
    }

    /** «زر الواي فاي» in the screensaver's settings: the pill comes in after «المصادر», or goes; the D-pad order follows. */
    private void showWifiPill(boolean on) {
        if (wifiPill == null || pillRow == null || on == pills.contains(wifiPill)) return;
        if (on) {
            pillRow.addView(wifiPill, pillRow.indexOfChild(pills.get(0)) + 1, llp(-2, dp(Pill.HEIGHT), dp(10), 0, 0, 0));
            pills.add(1, wifiPill);
        } else {
            if (wifiPill.hasFocus()) pills.get(0).requestFocus();
            pillRow.removeView(wifiPill);
            pills.remove(wifiPill);
        }
        wireFocus();
    }

    /** Shows or hides the «يُتلى الآن» card by what «تلاوة» is doing: a surah or a station (playing or paused), else nothing. */
    private void updateNowPlaying() {
        if (nowCard == null) return;
        NowPlaying.State s = NowPlaying.read(this);
        if (!s.show) {
            if (nowCard.getVisibility() == View.VISIBLE && nowCard.hasFocus() && !pills.isEmpty()) pills.get(0).requestFocus();   // it went with the playback
            nowCard.setVisibility(View.GONE);
            return;
        }
        nowCard.bind(s);
        nowCard.setVisibility(View.VISIBLE);
    }

    /** Pill button: its icon only; the one with the focus widens to show its name (see Pill). */
    private Pill pill(String label, int icon) {
        Pill p = new Pill(this, label, icon, font("sans-serif-medium"));
        p.setId(View.generateViewId());
        p.setOnFocusChangeListener((v, has) -> {
            if (has) {
                lastFocusedId = v.getId(); scroll.smoothScrollTo(0, 0); fadeGlow(1f);
                View card = homeCard == null ? null : homeCard.live();
                if (card != null) card.setNextFocusUpId(v.getId());   // up from the «المهام» card goes back to the pill it came from
            }
        });
        return p;
    }

    // ---------------------------------------------------------------- apps

    /** One small thread loads the apps' names, banners and glow colours, so the first frame (the clock, the pills, the wallpaper) never waits for them. */
    private final java.util.concurrent.ExecutorService loader = java.util.concurrent.Executors.newSingleThreadExecutor();
    private int appsGen;   // each buildApps() is a new generation: an older load that finishes late is dropped
    private boolean appsShown, entranceDue;
    static final int BATCH_ROWS = 2;   // grid rows added per pass of the UI thread

    private void buildApps() {
        PackageManager pm = getPackageManager();
        android.content.pm.ChangedPackages changed = pm.getChangedPackages(packageSeq);
        if (changed != null) { packageSeq = changed.getSequenceNumber(); artCache.evictAll(); }   // an app may have a new icon
        final int gen = ++appsGen;
        final String[] wanted = dockApps.clone();
        if (loader.isShutdown()) return;
        loader.execute(() -> {
            final List<App> all = loadApps(wanted);
            runOnUiThread(() -> { if (gen == appsGen && !isDestroyed()) showApps(all, wanted, gen); });
        });
    }

    /** Off the main thread: every launchable app with its label, art and colour (nothing here touches a view). */
    private List<App> loadApps(String[] wanted) {
        PackageManager pm = getPackageManager();
        List<App> out = new ArrayList<>();
        Map<String, String> given = AppNames.given(this);   // names the owner's agent set (dock.sh name)
        ParentalPolicy.State family = Parental.state(this);   // «وضع الأطفال»: only the allowed apps, when it is on
        for (ResolveInfo ri : Dock.catalogue(this)) {
            ActivityInfo ai = ri.activityInfo;
            if (!ParentalPolicy.visible(family, ai.packageName)) continue;
            App a = new App();
            a.pkg = ai.packageName;
            a.label = AppNames.label(this, ri, given);
            a.component = new ComponentName(ai.packageName, ai.name);
            a.category = Dock.isTv(ri) ? Intent.CATEGORY_LEANBACK_LAUNCHER : Intent.CATEGORY_LAUNCHER;
            Drawable banner = artCache.get("b:" + a.pkg);
            if (banner == null) {
                banner = ai.loadBanner(pm);
                if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
                if (banner != null) artCache.put("b:" + a.pkg, banner = fitted(banner, true));
            }
            a.isBanner = banner != null;
            if (banner != null) a.art = banner;
            else {
                a.art = artCache.get("i:" + a.pkg);
                if (a.art == null) artCache.put("i:" + a.pkg, a.art = fitted(ri.loadIcon(pm), false));
            }
            a.color = sampleColor(a.pkg, a.art);
            out.add(a);
        }
        return out;
    }

    /** On the main thread: the dock first, then the grid a few rows at a time, so a long list never holds up a frame. */
    private void showApps(List<App> all, String[] wanted, int gen) {
        Map<String, App> byPkg = new HashMap<>();
        for (App a : all) byPkg.put(a.pkg, a);
        List<App> dock = new ArrayList<>();
        for (String p : wanted) if (byPkg.containsKey(p)) dock.add(byPkg.remove(p));
        cardByPkg.clear();
        List<App> rest = new ArrayList<>(byPkg.values());
        Collections.sort(rest, (x, y) -> x.label.compareToIgnoreCase(y.label));
        // Fewer than MIN_DOCK of the chosen apps installed: top the dock up from the rest.
        while (dock.size() < MIN_DOCK && !rest.isEmpty()) dock.add(rest.remove(0));

        dockRow.removeAllViews();
        grid.removeAllViews();
        dockCards.clear();
        gridCards.clear();

        for (int i = 0; i < dock.size(); i++) {
            View tile = tile(dock.get(i), true);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
            p.setMarginStart(i == 0 ? 0 : dp(10));   // start/end: the row reads from the right, so left/right gave the end gaps half
            p.setMarginEnd(i == dock.size() - 1 ? 0 : dp(10));
            dockRow.addView(tile, p);
        }
        appsLabel.setVisibility(rest.isEmpty() ? View.GONE : View.VISIBLE);
        wireFocus();
        if (!dock.isEmpty()) { title.setText(dock.get(0).label); setGlow(dock.get(0).color); }
        View last = lastFocusedId == View.NO_ID ? null : findViewById(lastFocusedId);
        if (last != null) last.requestFocus();
        else if (!pills.isEmpty()) pills.get(0).requestFocus();
        appsShown = true;
        if (entranceDue) { entranceDue = false; playEntrance(); }
        applyPendingFocus();
        addGridRows(rest, 0, gen);
    }

    private void addGridRows(List<App> rest, int from, int gen) {
        if (gen != appsGen || isDestroyed()) return;
        int to = Math.min(rest.size(), from + BATCH_ROWS * cols);
        LinearLayout row = null;
        for (int i = from; i < to; i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                grid.addView(row, llp(-1, -2, 0, 0, 0, dp(26)));
            } else if (row == null) {
                row = (LinearLayout) grid.getChildAt(grid.getChildCount() - 1);
            }
            View tile = tile(rest.get(i), false);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
            p.setMarginStart(i % cols == 0 ? 0 : dp(10));
            p.setMarginEnd(i % cols == cols - 1 ? 0 : dp(10));
            row.addView(tile, p);
        }
        if (to < rest.size()) {
            wireFocus();
            handler.post(() -> addGridRows(rest, to, gen));
            return;
        }
        // Pad the last row so tiles keep their width.
        if (row != null) for (int i = rest.size() % cols; i != 0 && i < cols; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, 1, 1f);
            p.setMarginStart(dp(10));
            p.setMarginEnd(i == cols - 1 ? 0 : dp(10));
            row.addView(new View(this), p);
        }
        wireFocus();
        applyPendingFocus();
        focusPkgAfter = null;   // not found (the app is gone): never take the focus later
    }

    /** After «إضافة إلى المفضلة» and the like: the focus goes to the app that was changed, once its tile exists (the loads are in the background). */
    private String focusPkgAfter;
    private void applyPendingFocus() {
        if (focusPkgAfter == null) return;
        View c = cardByPkg.get(focusPkgAfter);
        if (c != null) { focusPkgAfter = null; c.requestFocus(); }
    }

    /** A 16:9 card with the app's banner; the name shows under it while focused. */
    private View tile(App app, boolean inDock) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);

        FrameLayout card = new AspectFrame(this);
        card.setId(View.generateViewId());
        card.setFocusable(true);
        card.setClickable(true);
        card.setTag(app);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10));
            }
        });
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(app.isBanner ? 0xFF1C1C1E : darken(app.color, 0.45f));
        card.setBackground(bg);

        ImageView img = new ImageView(this);
        img.setImageDrawable(app.art);
        if (app.isBanner) {
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        } else {
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
            img.setPadding(dp(22), dp(14), dp(22), dp(14));
        }

        // Focus ring and sheen, drawn above the art.
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(10));
        ring.setStroke(dp(2), 0xE6FFFFFF);
        ring.setOrientation(GradientDrawable.Orientation.TL_BR);
        ring.setColors(new int[]{0x26FFFFFF, 0x00FFFFFF, 0x00FFFFFF});
        View shine = new View(this);
        shine.setBackground(ring);
        shine.setAlpha(0f);
        card.addView(shine, new FrameLayout.LayoutParams(-1, -1));

        box.addView(card, new LinearLayout.LayoutParams(-1, -2));

        TextView label = text(11, "sans-serif-medium", Color.WHITE);
        label.setText(app.label);
        label.setSingleLine(true);
        label.setGravity(Gravity.CENTER);
        label.setAlpha(0f);
        if (!inDock) box.addView(label, llp(-1, -2, 0, dp(10), 0, 0));

        card.setOnClickListener(v -> guarded(inDock ? ParentalPolicy.TILE : ParentalPolicy.GRID, app.pkg, app.label, () -> launch(new Intent(Intent.ACTION_MAIN)
                .addCategory(app.category)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))));
        card.setOnFocusChangeListener((v, has) -> {
            float s = has ? (inDock ? 1.12f : 1.1f) : 1f;
            v.animate().scaleX(s).scaleY(s).translationZ(has ? dp(GlowLook.LIFT_DP) : 0)
                    .setInterpolator(has ? new OvershootInterpolator(1.8f) : new DecelerateInterpolator())
                    .setDuration(has ? 320 : 200).start();
            shine.animate().alpha(has ? 1f : 0f).setDuration(160).start();
            label.animate().alpha(has ? 1f : 0f).translationY(has ? dp(4) : 0).setDuration(200).start();
            if (!has) return;
            lastFocusedId = v.getId();
            if (inDock) {
                title.setText(app.label);
                title.animate().alpha(1f).setDuration(200).start();
                animateGlow(app.color);
                fadeGlow(0.6f);
                scroll.smoothScrollTo(0, Math.max(0, bottomBlock.getTop() - dp(56)));   // the dock at the top, the apps under it
            } else {
                fadeGlow(0.35f);
                title.animate().alpha(0f).setDuration(200).start();
                scrollToRow(box);
            }
        });

        // Long-press OK: add to / remove from / reorder the dock, right from the remote.
        card.setOnLongClickListener(v -> { showAppMenu(v, app.pkg, inDock); return true; });
        cardByPkg.put(app.pkg, card);

        (inDock ? dockCards : gridCards).add(card);
        return box;
    }

    /** Explicit D-pad targets: one row per press, ends of rows and of the top bar don't wrap. */
    private void wireFocus() {
        int d = dockCards.size(), g = gridCards.size();
        // the «المهام» card, when it is among the home cards, is a stop between the pills and the dock
        View card = homeCard == null ? null : homeCard.live();
        int dockId = d > 0 ? dockCards.get(Math.min(d - 1, d / 2)).getId() : View.NO_ID;
        for (int i = 0; i < pills.size(); i++) {
            View p = pills.get(i);
            p.setNextFocusLeftId(i == 0 ? p.getId() : pills.get(i - 1).getId());
            p.setNextFocusRightId(i == pills.size() - 1 ? p.getId() : pills.get(i + 1).getId());
            p.setNextFocusUpId(p.getId());
            p.setNextFocusDownId(card != null ? card.getId() : dockId != View.NO_ID ? dockId : p.getId());
        }
        int topId = card != null ? card.getId() : pills.get(0).getId();
        if (nowCard != null) {   // the «يُتلى الآن» card: down to the cards or the dock, left and up to the pills (right: the end of the row)
            nowCard.setNextFocusLeftId(pills.get(0).getId());
            nowCard.setNextFocusUpId(pills.get(0).getId());
            nowCard.setNextFocusRightId(nowCard.getId());
            nowCard.setNextFocusDownId(card != null ? card.getId() : dockId != View.NO_ID ? dockId : nowCard.getId());
        }
        if (card != null) {
            card.setNextFocusLeftId(card.getId());   // nothing beside it to move to: stay on it
            card.setNextFocusRightId(card.getId());
            card.setNextFocusUpId(pills.get(0).getId());   // a pill's own focus moves this to that pill, see pill()
            card.setNextFocusDownId(dockId != View.NO_ID ? dockId : card.getId());
        }
        for (int i = 0; i < d; i++) {
            View c = dockCards.get(i);
            c.setNextFocusUpId(topId);
            c.setNextFocusLeftId(i == 0 ? c.getId() : dockCards.get(i - 1).getId());
            c.setNextFocusRightId(i == d - 1 ? c.getId() : dockCards.get(i + 1).getId());
            c.setNextFocusDownId(g > 0 ? gridCards.get(Math.min(i, g - 1)).getId() : c.getId());
        }
        int lastRow = (g - 1) / cols;
        for (int j = 0; j < g; j++) {
            View c = gridCards.get(j);
            int r = j / cols, col = j % cols;
            c.setNextFocusUpId(r == 0
                    ? (d > 0 ? dockCards.get(Math.min(col, d - 1)).getId() : topId)
                    : gridCards.get(j - cols).getId());
            c.setNextFocusDownId(j + cols < g ? gridCards.get(j + cols).getId()
                    : r < lastRow ? gridCards.get(g - 1).getId() : c.getId());
            c.setNextFocusLeftId(col == 0 ? c.getId() : gridCards.get(j - 1).getId());
            c.setNextFocusRightId(col == cols - 1 || j == g - 1 ? c.getId() : gridCards.get(j + 1).getId());
        }
    }

    private void scrollToRow(View tileBox) {
        View row = (View) tileBox.getParent();
        int y = grid.getTop() + row.getTop();
        int screenH = getResources().getDisplayMetrics().heightPixels;
        // First grid row: slide the dock band up to the top edge; later rows centre on screen.
        View band = (View) dockRow.getParent();
        int target = grid.indexOfChild(row) == 0
                ? hero.getTop() + band.getTop() + dockRow.getTop() - dp(22)
                : y - (int) (screenH * 0.42f);
        int max = Math.max(0, content.getHeight() - scroll.getHeight());
        scroll.smoothScrollTo(0, Math.max(0, Math.min(target, max)));
    }

    // ---------------------------------------------------------------- inputs

    // ---------------------------------------------------------------- dock editing

    private final Map<String, View> cardByPkg = new HashMap<>();
    static final int MIN_DOCK = Dock.MIN, MAX_DOCK = Dock.MAX;

    /** The user's dock if they edited it, else res/values/config.xml. */
    /** The launcher's settings file, shared with DockReceiver. */
    private android.content.SharedPreferences prefs() { return Dock.prefs(this); }

    private String[] loadDock() {
        String saved = prefs().getString("dock", null);
        return saved != null ? (saved.isEmpty() ? new String[0] : saved.split(","))
                             : getResources().getStringArray(R.array.dock_apps);
    }

    /** Only installed apps are kept, so the dock never holds a removed app's slot. */
    private void saveDock(List<String> dock, String focusPkg) {
        List<String> installed = new ArrayList<>();
        for (String p : dock) if (isInstalled(p)) installed.add(p);
        prefs().edit().putString("dock", android.text.TextUtils.join(",", installed)).apply();
        dockApps = installed.toArray(new String[0]);
        focusPkgAfter = focusPkg;
        buildApps();
    }

    private void showAppMenu(View anchor, String pkg, boolean inDock) {
        guarded(ParentalPolicy.MENU, null, "قائمة التطبيق", () -> showAppMenuNow(anchor, pkg, inDock));
    }

    private void showAppMenuNow(View anchor, String pkg, boolean inDock) {
        List<String> dock = new ArrayList<>();
        // The dock as shown, including apps added automatically to reach MIN_DOCK.
        for (View c : dockCards) for (Map.Entry<String, View> e : cardByPkg.entrySet()) if (e.getValue() == c) dock.add(e.getKey());
        int i = dock.indexOf(pkg);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add("ترتيب المفضلة…");
        actions.add(() -> launchGuarded(ParentalPolicy.MENU_ARRANGE, new Intent(this, DockActivity.class), "ترتيب المفضلة"));
        labels.add("إدارة التطبيقات…");
        actions.add(() -> launchGuarded(ParentalPolicy.PARENTAL_SCREEN, new Intent(this, AppsActivity.class), "إدارة التطبيقات"));
        if (inDock) {
            if (dock.size() > MIN_DOCK) {
                labels.add("إزالة من المفضلة");
                actions.add(() -> { dock.remove(pkg); saveDock(dock, dock.get(Math.max(0, i - 1))); });
            } else {
                labels.add("المفضلة لا تقل عن " + arabicDigits(String.valueOf(MIN_DOCK)));
                actions.add(() -> { });
            }
        } else if (dock.size() < MAX_DOCK) {
            labels.add("إضافة إلى المفضلة");
            actions.add(() -> { dock.add(pkg); saveDock(dock, pkg); });
        } else {
            labels.add("المفضلة ممتلئة (" + MAX_DOCK + ")");
            actions.add(() -> { });
        }
        labels.add("إعادة المفضلة الافتراضية");
        actions.add(() -> {
            prefs().edit().remove("dock").apply();
            dockApps = getResources().getStringArray(R.array.dock_apps);
            buildApps();
        });
        labels.add("الرقابة الأبوية…");
        actions.add(() -> launchGuarded(ParentalPolicy.PARENTAL_SCREEN, new Intent(this, ParentalActivity.class), "الرقابة الأبوية"));
        showMenu(anchor, labels, actions);
    }

    /** Small focusable popup in the same style as the Inputs menu. */
    private void showMenu(View anchor, List<String> labels, List<Runnable> actions) {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(0xF02A2A2E);
        bg.setStroke(dp(1), 0x26FFFFFF);
        list.setBackground(bg);
        PopupWindow pw = new PopupWindow(list, dp(230), -2, true);
        List<TextView> rows = new ArrayList<>();
        for (int k = 0; k < labels.size(); k++) rows.add(inputRow(labels.get(k), actions.get(k)));
        for (TextView r : rows) {
            r.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
            r.setOnClickListener(v -> { pw.dismiss(); ((Runnable) v.getTag()).run(); });
            list.addView(r, new LinearLayout.LayoutParams(-1, dp(36)));
        }
        for (int k = 0; k < rows.size(); k++) {
            TextView r = rows.get(k);
            r.setNextFocusUpId(rows.get(Math.max(0, k - 1)).getId());
            r.setNextFocusDownId(rows.get(Math.min(rows.size() - 1, k + 1)).getId());
            r.setNextFocusLeftId(r.getId());
            r.setNextFocusRightId(r.getId());
        }
        pw.setOutsideTouchable(true);
        pw.setElevation(dp(16));
        pw.showAsDropDown(anchor, 0, -anchor.getHeight() - dp(8) * (rows.size() + 2) - dp(36) * rows.size(), Gravity.START);
        rows.get(0).requestFocus();
    }

    private void showInputs(View anchor, int path) {
        guarded(path, null, "المصادر", () -> showInputsNow(anchor));
    }

    private void showInputsNow(View anchor) {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(0xF02A2A2E);
        bg.setStroke(dp(1), 0x26FFFFFF);
        list.setBackground(bg);

        PopupWindow pw = new PopupWindow(list, dp(200), -2, true);
        List<TextView> rows = new ArrayList<>();

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        List<TvInputInfo> inputs = new ArrayList<>();
        if (tim != null) for (TvInputInfo t : tim.getTvInputList()) {
            if (t.isPassthroughInput() && !t.isHidden(this)) inputs.add(t);
        }
        Collections.sort(inputs, (x, y) -> inputLabel(x).compareToIgnoreCase(inputLabel(y)));
        for (TvInputInfo t : inputs) {
            rows.add(inputRow(inputLabel(t), () -> openPassthroughInput(t)));
        }
        if (isInstalled(liveTvPkg)) rows.add(inputRow("البث التلفزيوني", () -> guarded(ParentalPolicy.INPUT_LIVE_TV, liveTvPkg, "البث التلفزيوني", () -> openPackage(liveTvPkg))));
        if (inputs.isEmpty() && isInstalled(inputPickerPkg)) rows.add(inputRow("كل المصادر…", () -> guarded(ParentalPolicy.INPUT_PICKER, inputPickerPkg, "كل المصادر", () -> openPackage(inputPickerPkg))));

        if (rows.isEmpty()) rows.add(inputRow("الإعدادات", () -> launchGuarded(ParentalPolicy.PILL_TV_SETTINGS, new Intent(Settings.ACTION_SETTINGS), "إعدادات التلفزيون")));
        for (TextView r : rows) {
            r.setOnClickListener(v -> { pw.dismiss(); ((Runnable) v.getTag()).run(); });
            list.addView(r, new LinearLayout.LayoutParams(-1, dp(36)));
        }
        for (int i = 0; i < rows.size(); i++) {
            TextView r = rows.get(i);
            r.setNextFocusUpId(rows.get(Math.max(0, i - 1)).getId());
            r.setNextFocusDownId(rows.get(Math.min(rows.size() - 1, i + 1)).getId());
            r.setNextFocusLeftId(r.getId());
            r.setNextFocusRightId(r.getId());
        }
        pw.setOutsideTouchable(true);
        pw.setElevation(dp(16));
        pw.setOnDismissListener(() -> { inputsPopup = null; anchor.requestFocus(); });
        inputsPopup = pw;
        pw.showAsDropDown(anchor, 0, dp(8), Gravity.START);
        rows.get(0).requestFocus();
    }

    private String inputLabel(TvInputInfo t) {
        CharSequence c = t.loadCustomLabel(this);
        if (c == null || c.length() == 0) c = t.loadLabel(this);
        return String.valueOf(c);
    }

    private TextView inputRow(String label, Runnable action) {
        TextView r = text(13, "sans-serif", Color.WHITE);
        r.setText(label);
        r.setTag(action);
        r.setId(View.generateViewId());
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(14), 0, dp(14), 0);
        r.setFocusable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(Color.TRANSPARENT);
        r.setBackground(bg);
        r.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : Color.TRANSPARENT);
            r.setTextColor(has ? 0xFF111111 : Color.WHITE);
        });
        return r;
    }

    // ---------------------------------------------------------------- status

    // The two date formats are made once and given the city's zone when it changes: the tick runs every minute.
    private android.icu.text.SimpleDateFormat hijriDay;
    private SimpleDateFormat gregFormat;
    private String formatZone = "";

    private void updateClock() {
        long ms = System.currentTimeMillis();
        String tvTz = java.util.TimeZone.getDefault().getID();
        // one read of the screensaver's settings (its ConfigProvider, same signing key) for the whole tick; null zone = it is missing: the TV's own
        ConfigSnapshot cfg = ConfigSnapshot.read(this);
        Digits.remember(this, cfg);   // a change of the setting reaches the next tick
        String[] zone = cfg.cityZone();
        String tz = zone == null ? tvTz : zone[1];
        Calendar now = ClockZone.at(ms, tz);   // the city's time, like the prayer times beside it
        int h = now.get(Calendar.HOUR_OF_DAY);
        greetingLine.update(h, cfg);
        if (goldLine != null) goldLine.update(Digits.latin());
        boolean h24 = cfg.clock24();   // «ساعة ٢٤»: off (12-hour with the ص or م) unless the owner turned it on
        clock.setText(ClockText.face(h, now.get(Calendar.MINUTE), h24, Digits.latin()));
        ampm.setText(ClockText.suffix(h, h24));
        ampm.setVisibility(h24 ? View.GONE : View.VISIBLE);
        if (gregFormat == null) {
            gregFormat = new SimpleDateFormat("EEEE، d MMMM", new Locale("ar"));
            try {
                hijriDay = new android.icu.text.SimpleDateFormat("d MMMM", new android.icu.util.ULocale("ar_SA@calendar=islamic-umalqura"));
            } catch (Exception ignored) { }
        }
        if (!tz.equals(formatZone)) {
            gregFormat.setTimeZone(java.util.TimeZone.getTimeZone(tz));
            if (hijriDay != null) hijriDay.setTimeZone(android.icu.util.TimeZone.getTimeZone(tz));
            formatZone = tz;
        }
        String hijri = "";
        try {
            int shift = HijriShift.fromFragment(cfg.fragment);   // «فرق التاريخ الهجري»: 0 unless the settings say so
            if (hijriDay != null) hijri = hijriDay.format(shift == 0 ? now.getTime() : HijriShift.date(now, shift)).replaceAll("\\s*هـ\\s*$", "");
        } catch (Exception ignored) { }
        String greg = gregFormat.format(now.getTime());
        date.setText(arabicDigits(hijri.isEmpty() ? greg : greg + "  –  " + hijri));
        updateAdhanBar(cfg);
        showWifiPill(cfg.wifiPillOn);
        sa.urtv.home.tilawah.AdhanSync.onConfig(this, cfg.fragment);   // «تعديل وقت الصلاة» changed on the TV: the alarm follows
    }

    /** Once a minute with the clock: the bar before the adhan shows in the last 10 minutes, and until the iqama. */
    private void updateAdhanBar(ConfigSnapshot cfg) {
        String words = AdhanBar.text(this, cfg);
        adhanBar.setVisibility(words == null ? View.GONE : View.VISIBLE);
        if (words != null) adhanBar.setText(words);
        // any key stops the adhan (AdhanKeys) and the focus stays where it was; with «إيقاف الأذان بأي زر» off the bar is focusable (UP reaches it) and OK on it stops it
        adhanBar.setFocusable(words != null && AdhanBar.playing(cfg));
    }

    private static String arabicDigits(String s) { return Digits.of(s); }   // the setting's digits (hindi or latin)

    /** Connected or not comes from ConnectivityManager: WifiManager hides the network (id -1, "<unknown ssid>")
     *  from apps without location access, which looked like "not connected". The name only when readable. */
    private void updateWifi() {
        if (wifiPill == null) return;
        boolean wired = false, wifi = false;
        String ssid = null;
        try {
            android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
            android.net.NetworkCapabilities nc = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
            wired = nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET);
            wifi = nc != null && !wired && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI);
            if (wifi) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                ssid = wm == null || wm.getConnectionInfo() == null ? null : wm.getConnectionInfo().getSSID();
            }
        } catch (SecurityException ignored) { }
        wifiPill.setLabel(PillText.network(wired, wifi, ssid));   // shown only while the pill has the focus
    }

    // ---------------------------------------------------------------- glow

    private void setGlow(int color) {
        glowColor = color;
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientCenter(0.5f, 0.78f);
        g.setGradientRadius(getResources().getDisplayMetrics().widthPixels * GlowLook.RADIUS);
        g.setColors(new int[]{withAlpha(color, GlowLook.CENTRE_ALPHA), withAlpha(color, GlowLook.MID_ALPHA), Color.TRANSPARENT});
        glow.setBackground(g);
    }

    private void animateGlow(int to) {
        if (glowAnim != null) glowAnim.cancel();
        glowAnim = ValueAnimator.ofObject(new ArgbEvaluator(), glowColor, to);
        glowAnim.setDuration(450);
        glowAnim.addUpdateListener(a -> setGlow((int) a.getAnimatedValue()));
        glowAnim.start();
    }

    private void fadeGlow(float alpha) {
        glow.animate().alpha(alpha).setDuration(300).start();
        // The grid sits over the photo's busiest middle part, so dim it while browsing apps.
        gridShade.animate().alpha(alpha < 1f ? 1f : 0f).setDuration(300).start();
    }

    // ---------------------------------------------------------------- wallpaper

    private ImageView wallpaperLayer() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    // Photos from the internet: Bing's photos of the last 8 days (nature, cities, landscapes; one new a day, no key). The list
    // is asked for once a day, each photo once and kept; while they are there the bundled photos are only the fallback
    // (offline, or a photo not downloaded yet). Nothing comes from GitHub.
    static final String PHOTO_HOST = "https://www.bing.com";
    static final String PHOTO_FEED = PHOTO_HOST + "/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=en-US";
    static final long INDEX_MAX_AGE_MS = 24 * 3600 * 1000L;

    static final int PHOTO_SET = 8, PHOTO_PERIOD_DAYS = 1;

    /** Where a kept photo's name comes from: "name|/th?id=…" lines, written with the list. */
    private String photoUrl(String name) {
        for (String row : prefs().getString("photoUrls", "").split("\n")) {
            int bar = row.indexOf('|');
            if (bar > 0 && row.substring(0, bar).equals(name)) return PHOTO_HOST + row.substring(bar + 1) + "_1920x1080.jpg";
        }
        return null;
    }

    /** The day's hosted photos, in a Park-Miller order that changes once a day. */
    private String[] remotePhotos() {
        String s = prefs().getString("photoIndex", "");
        if (s.isEmpty()) return new String[0];
        String[] all = s.split(",");
        java.util.Calendar c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Riyadh"));
        java.util.Calendar utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        utc.clear();
        utc.set(c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH));
        long day = Math.floorDiv(utc.getTimeInMillis(), 86400000L);
        long seed = (Math.floorDiv(day, PHOTO_PERIOD_DAYS) % 2147483646L) + 1;
        List<String> pick = new ArrayList<>();
        for (int guard = 0; pick.size() < Math.min(PHOTO_SET, all.length) && guard < 1000; guard++) {
            seed = (seed * 48271L) % 2147483647L;
            String f = all[(int) (seed % all.length)];
            if (!pick.contains(f)) pick.add(f);
        }
        return pick.toArray(new String[0]);
    }

    /** Keep only the current set on disk. */
    private void pruneCachedPhotos(String[] keep) {
        java.io.File[] files = new java.io.File(getFilesDir(), "photos").listFiles();
        if (files == null) return;
        java.util.Set<String> k = new java.util.HashSet<>(Arrays.asList(keep));
        for (java.io.File f : files) if (!k.contains(f.getName())) f.delete();
    }

    /** a photo or the index that failed is not asked for again for this long: not at every rotation of the wallpaper */
    static final long RETRY_MS = 60 * 60 * 1000L;
    private final Map<String, Long> photoFailed = new HashMap<>();

    private boolean failedRecently(String what) {
        Long at = photoFailed.get(what);
        return at != null && System.currentTimeMillis() - at < RETRY_MS;
    }

    /** Off the main thread: refresh the hosted photo list once a day (after a failure, again in an hour). */
    private void refreshPhotoIndex() {
        if (System.currentTimeMillis() - prefs().getLong("photoIndexAt", 0) < INDEX_MAX_AGE_MS) return;
        if (failedRecently("index.json")) return;
        try {
            org.json.JSONArray a = new org.json.JSONObject(new String(download(PHOTO_FEED, Bounded.JSON_MAX), "UTF-8")).getJSONArray("images");
            List<String> names = new ArrayList<>();
            StringBuilder urls = new StringBuilder();
            for (int i = 0; i < a.length(); i++) {
                String base = a.getJSONObject(i).optString("urlbase");   // "/th?id=OHR.WadiRum_EN-US1234567890"
                if (!base.matches("/th\\?id=[A-Za-z0-9._-]+")) continue;
                String f = base.substring(base.indexOf('=') + 1).replaceAll("[^A-Za-z0-9_-]", "_") + ".jpg";
                names.add(f);
                urls.append(f).append('|').append(base).append('\n');
            }
            if (!names.isEmpty()) prefs().edit()
                    .putString("photoIndex", android.text.TextUtils.join(",", names))
                    .putString("photoUrls", urls.toString())
                    .putLong("photoIndexAt", System.currentTimeMillis()).apply();
        } catch (Exception ignored) {
            photoFailed.put("index.json", System.currentTimeMillis());
        }
    }

    /** At most max bytes (Bounded): the photo list is JSON_MAX, a photo IMAGE_MAX; a longer answer is an IOException and nothing is kept. */
    private static byte[] download(String url, int max) throws java.io.IOException {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        try {
            if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
            if (c.getContentLength() > max) throw new java.io.IOException("too large");
            try (java.io.InputStream in = c.getInputStream()) { return Bounded.readAll(in, max); }
        } finally {
            c.disconnect();
        }
    }

    /** Whether these bytes are a picture that decodes (its size is read, not the pixels) and is not absurdly large. */
    private static boolean validImage(byte[] data) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        return o.outWidth > 0 && o.outHeight > 0 && o.outWidth <= 12000 && o.outHeight <= 12000;
    }

    /** Opens the same photo again: the first pass reads only its size. */
    private interface Source { java.io.InputStream open() throws java.io.IOException; }

    /** A photo at about the screen's width: a 4000 px original is not held in memory to be shown on 1920 px. */
    private Bitmap decode(Source source) throws java.io.IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (java.io.InputStream in = source.open()) {
            if (in == null) return null;
            BitmapFactory.decodeStream(in, null, o);
        }
        o.inJustDecodeBounds = false;
        o.inSampleSize = Bounded.sampleSize(o.outWidth, getResources().getDisplayMetrics().widthPixels);
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        try (java.io.InputStream in = source.open()) {
            return in == null ? null : BitmapFactory.decodeStream(in, null, o);
        }
    }

    // The owner's own photos («صوري»), read from the screensaver app's ConfigProvider (READ_CONFIG, read only).
    static final Uri OWN = Uri.parse("content://sa.urtv.screensaver.config/own");

    /** {how many, mode, rev}: none, mix and 0 when the screensaver app has none or does not answer */
    private Object[] ownInfo() {
        try (Cursor c = getContentResolver().query(OWN, null, null, null, null)) {
            // an older screensaver app answers any path with its settings instead: no "n" column, no photos
            if (c != null && c.getColumnIndex("n") == 0 && c.moveToFirst()) return new Object[] {c.getInt(0), c.getString(1), c.getLong(2)};
        } catch (RuntimeException ignored) { }
        return new Object[] {0, "mix", 0L};
    }

    /** Off the main thread: when the owner's photos changed since the wallpaper was last chosen, show one now. */
    private void checkOwnChanged() {
        new Thread(() -> {
            long rev = (Long) ownInfo()[2];
            if (rev != prefs().getLong("ownRev", 0)) runOnUiThread(() -> { if (!isDestroyed()) nextWallpaper(); });
        }).start();
    }

    /** Decode the next photo off the main thread, then crossfade it in over the current one. */
    private void nextWallpaper() {
        handler.removeCallbacks(rotateWallpaper);
        if (wallpapers.length == 0) return;
        final Object[] own = ownInfo();
        final int ownCount = (Integer) own[0];
        final boolean only = ownCount > 0 && "only".equals(own[1]);
        prefs().edit().putLong("ownRev", (Long) own[2]).apply();
        final String[] remote = only ? new String[0] : remotePhotos();
        final int bundled = remote.length > 0 ? 0 : wallpapers.length;   // the internet's photos when there are any, else the bundled ones
        final int base = only ? 0 : bundled + remote.length;   // those first, then the owner's
        final int total = only ? ownCount : base + ownCount;
        if (wallIndex < 0) wallIndex = prefs().getInt("wallpaper", -1);
        wallIndex = (wallIndex + 1) % total;
        prefs().edit().putInt("wallpaper", wallIndex).apply();
        wallChangedAt = SystemClock.elapsedRealtime();
        final int idx = wallIndex;
        final String fallback = "wallpapers/" + wallpapers[idx % wallpapers.length];
        final int gen = ++wallGen;   // a photo that arrives after a newer request is not shown
        new Thread(() -> {
            // 1. what is on the device now, at once: the owner's photo, the hosted one already kept, else the bundled one
            Bitmap bm = null;
            java.io.File f = null;
            if (idx >= base) {
                final Uri photo = OWN.buildUpon().appendPath(String.valueOf(idx - base + 1)).build();
                try {
                    bm = decode(() -> getContentResolver().openInputStream(photo));
                } catch (java.io.IOException | RuntimeException ignored) { }
            } else if (idx >= bundled) {
                f = new java.io.File(getFilesDir(), "photos/" + remote[idx - bundled]);
                if (f.isFile()) {
                    final java.io.File kept = f;
                    try { bm = decode(() -> new java.io.FileInputStream(kept)); } catch (java.io.IOException | RuntimeException ignored) { }
                }
            }
            final boolean needsDownload = f != null && bm == null;
            if (bm == null) {
                try { bm = decode(() -> getAssets().open(fallback)); }
                catch (java.io.IOException ignored) { }
            }
            showWallpaperIfCurrent(bm, gen);
            if (only) return;   // no network while only the owner's photos show
            // 2. then the network: the photo list (once a day), the cache pruned, and the hosted photo if it was missing
            refreshPhotoIndex();
            pruneCachedPhotos(remotePhotos());
            if (!needsDownload || !Arrays.asList(remotePhotos()).contains(f.getName()) || failedRecently(f.getName())) return;
            final String url = photoUrl(f.getName());
            if (url == null) return;
            try {
                byte[] data;
                try { data = download(url, Bounded.IMAGE_MAX); }
                catch (java.io.IOException e) { photoFailed.put(f.getName(), System.currentTimeMillis()); throw e; }
                if (!validImage(data)) { photoFailed.put(f.getName(), System.currentTimeMillis()); return; }   // not kept, not asked for again for an hour
                java.io.File dir = f.getParentFile();
                if (dir.isDirectory() || dir.mkdirs()) {
                    java.io.File tmp = new java.io.File(dir, f.getName() + ".tmp");
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) { out.write(data); }
                    if (!tmp.renameTo(f)) tmp.delete();
                }
                final java.io.File got = f;
                Bitmap fresh = got.isFile() ? decode(() -> new java.io.FileInputStream(got)) : null;
                showWallpaperIfCurrent(fresh, gen);
            } catch (java.io.IOException | RuntimeException ignored) { }
        }).start();
        scheduleWallpaper(WALLPAPER_INTERVAL_MS);
    }

    private int wallGen;   // counts the requests for a wallpaper (main thread)

    /** From the loading thread: shown only if no newer request has been made since this one started. */
    private void showWallpaperIfCurrent(Bitmap bm, int gen) {
        if (bm != null) runOnUiThread(() -> { if (gen == wallGen) showWallpaper(bm); });
    }

    private void showWallpaper(Bitmap bm) {
        if (bm == null || isDestroyed()) return;
        // Swap roles: the old front becomes the back, the new photo fades in on top.
        final ImageView old = wallFront;
        wallFront = wallBack;
        wallBack = old;
        ViewGroup root = (ViewGroup) wallFront.getParent();
        root.removeView(wallFront);
        root.addView(wallFront, 1, new FrameLayout.LayoutParams(-1, -1));
        wallFront.setImageBitmap(bm);
        wallFront.setAlpha(0f);
        final ImageView shown = wallFront;
        shown.animate().alpha(1f).setDuration(firstWallpaper ? 600 : 2000).withEndAction(() -> {
            // release the previous photo: the view this fade was above, not whichever is behind by now (a newer photo may have swapped them)
            if (old != wallFront && old != shown) old.setImageDrawable(null);
        }).start();
        firstWallpaper = false;
    }

    private boolean firstWallpaper = true;

    private void scheduleWallpaper(long delay) {
        handler.removeCallbacks(rotateWallpaper);
        handler.postDelayed(rotateWallpaper, Math.max(1000, delay));
    }

    /** The art redrawn at the size a tile shows it (never enlarged); a banner fills the 16:9 card, an icon fits inside it. */
    private Drawable fitted(Drawable d, boolean banner) {
        int w = Math.round(getResources().getDisplayMetrics().widthPixels / (float) MIN_DOCK * 1.12f), h = w * 9 / 16;   // the focused tile grows 12%
        int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
        if (iw <= 0 || ih <= 0) return d;
        float f = banner ? Math.max(w / (float) iw, h / (float) ih) : Math.min(h / (float) iw, h / (float) ih);
        if (f >= 1f) return d;
        try {
            int bw = banner ? w : Math.max(1, Math.round(iw * f)), bh = banner ? h : Math.max(1, Math.round(ih * f));
            Bitmap bm = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            int dw = Math.round(iw * f), dh = Math.round(ih * f);
            d.setBounds((bw - dw) / 2, (bh - dh) / 2, (bw - dw) / 2 + dw, (bh - dh) / 2 + dh);
            d.draw(c);
            return new BitmapDrawable(getResources(), bm);
        } catch (RuntimeException | OutOfMemoryError e) {
            return d;
        }
    }

    /** Average the banner's vivid pixels, then push saturation so the glow reads as a colour. */
    private int sampleColor(String pkg, Drawable d) {
        Integer cached = colorCache.get(pkg);
        if (cached != null) return cached;
        int color = 0xFF3A3A3A;
        try {
            Bitmap bm = Bitmap.createBitmap(24, 14, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            d.setBounds(0, 0, 24, 14);
            d.draw(c);
            float[] hsv = new float[3];
            double r = 0, g = 0, b = 0, w = 0;
            for (int y = 0; y < 14; y++) for (int x = 0; x < 24; x++) {
                int p = bm.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                Color.colorToHSV(p, hsv);
                double weight = 0.05 + hsv[1] * hsv[2];
                r += Color.red(p) * weight; g += Color.green(p) * weight; b += Color.blue(p) * weight; w += weight;
            }
            bm.recycle();
            if (w > 0) {
                Color.colorToHSV(Color.rgb((int) (r / w), (int) (g / w), (int) (b / w)), hsv);
                if (hsv[1] > 0.12f) { hsv[1] = Math.max(hsv[1], 0.75f); hsv[2] = 0.85f; }
                else { hsv[1] = 0f; hsv[2] = 0.45f; }
                color = Color.HSVToColor(hsv);
            }
        } catch (RuntimeException ignored) { }
        colorCache.put(pkg, color);
        return color;
    }

    // ---------------------------------------------------------------- entrance

    private void playEntrance() {
        View header = hero.getChildAt(0);
        header.setAlpha(0f);
        header.setTranslationY(-dp(12));
        header.animate().alpha(1f).translationY(0).setDuration(500).setStartDelay(80).start();
        title.setAlpha(0f);
        title.animate().alpha(1f).setDuration(500).setStartDelay(200).start();
        for (int i = 0; i < dockRow.getChildCount(); i++) {
            View t = dockRow.getChildAt(i);
            t.setAlpha(0f);
            t.setTranslationY(dp(24));
            t.animate().alpha(1f).translationY(0).setInterpolator(new DecelerateInterpolator(2f))
                    .setDuration(450).setStartDelay(160 + i * 60L).start();
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Switch to a passthrough input (HDMI1, HDMI2, AV, …). The standard
     *  TvContract passthrough URI only resolves when a Live-TV app registers
     *  it as implicit; some vendor TV apps (TCL's com.tcl.tv/.TVActivity on
     *  this hardware) handle it only when the intent names the component
     *  explicitly. Try the implicit intent first, then every candidate
     *  activity in the configured live_tv_package, then fall back to the
     *  input picker package. */
    private void openPassthroughInput(TvInputInfo t) {
        Intent base = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(t.getId()));
        base.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (launch(base)) return;
        if (liveTvPkg != null && !liveTvPkg.isEmpty() && isInstalled(liveTvPkg)) {
            // Query every activity in the Live-TV package that advertises VIEW
            // and try each with the URI, newest APIs first.
            Intent probe = new Intent(Intent.ACTION_VIEW).setPackage(liveTvPkg);
            List<ResolveInfo> handlers = getPackageManager().queryIntentActivities(probe,
                    PackageManager.MATCH_DEFAULT_ONLY);
            for (ResolveInfo ri : handlers) {
                Intent explicit = new Intent(Intent.ACTION_VIEW,
                        TvContract.buildChannelUriForPassthroughInput(t.getId()));
                explicit.setClassName(ri.activityInfo.packageName, ri.activityInfo.name);
                explicit.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (launch(explicit)) return;
            }
        }
        guarded(ParentalPolicy.INPUT_PICKER, inputPickerPkg, "كل المصادر", () -> openPackage(inputPickerPkg));
    }

    // ---------------------------------------------------------------- the family lock (Parental)

    /**
     * Everything the home screen opens for a person comes through here: it runs {@code go} now, or after the PIN when one of the
     * path's targets is locked (ParentalPolicy). With nothing set it is a plain call.
     */
    private void guarded(int path, String pkg, String name, Runnable go) {
        Parental.guard(this, this::adhanTakes, name, ParentalPolicy.targets(path, pkg), go, () -> { });
    }

    /** An intent behind a pill or the menu: locked by the pill's own name, or by the app it opens. */
    private void launchGuarded(int path, Intent i, String name) {
        guarded(path, packageOf(i), name, () -> launch(i));
    }

    private String packageOf(Intent i) {
        if (i.getComponent() != null) return i.getComponent().getPackageName();
        ResolveInfo ri = getPackageManager().resolveActivity(i, 0);
        return ri == null || ri.activityInfo == null ? null : ri.activityInfo.packageName;
    }

    private boolean launch(Intent i) {
        try {
            if (!i.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean isInstalled(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void openPackage(String pkg) {
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) launch(i);
    }

    /** The screensaver now: its full-screen preview (the same page; Home or Back leaves it). An older app without it opens its settings. */
    private void startSaver() {
        Intent i = new Intent().setComponent(new ComponentName(SAVER_PKG, SAVER_PKG + ".MainActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);   // fresh each time, with today's settings
        if (getPackageManager().resolveActivity(i, 0) != null) launch(i); else openPackage(SAVER_PKG);
    }

    static final int GOLD = 0xFFD6AF6B;
    static final String SAVER_PKG = "sa.urtv.screensaver";
    private final Map<String, Typeface> fonts = new HashMap<>();

    /** Thmanyah from assets/fonts; any old family name maps to the nearest weight. */
    private Typeface font(String family) {
        String file = family.contains("display") ? "ThmanyahDisplay-Bold"
                : family.contains("thin") || family.contains("light") ? "Thmanyah-Light"
                : family.contains("medium") ? "Thmanyah-Medium" : "Thmanyah-Regular";
        Typeface tf = fonts.get(file);
        if (tf == null) {
            try { tf = Typeface.createFromAsset(getAssets(), "fonts/" + file + ".otf"); }
            catch (RuntimeException e) {   // no Thmanyah in this build (it comes from a private repo): IBM Plex Sans Arabic
                String plex = file.endsWith("Display-Bold") || file.endsWith("Light") ? "Light" : file.endsWith("Medium") ? "Medium" : "Regular";
                try { tf = Typeface.createFromAsset(getAssets(), "plex/IBMPlexSansArabic-" + plex + ".ttf"); }
                catch (RuntimeException e2) { tf = Typeface.create("sans-serif", Typeface.NORMAL); }
            }
            fonts.put(file, tf);
        }
        return tf;
    }

    private TextView text(float sp, String family, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(font(family));
        t.setTextColor(color);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static FrameLayout.LayoutParams lp(int w, int h, int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h, gravity);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static LinearLayout.LayoutParams llp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static int withAlpha(int c, float a) {
        return Color.argb(Math.round(a * 255), Color.red(c), Color.green(c), Color.blue(c));
    }

    private static int darken(int c, float f) {
        return Color.rgb((int) (Color.red(c) * f), (int) (Color.green(c) * f), (int) (Color.blue(c) * f));
    }

    /** The adhan's rule for a key; the PIN keypad (a dialog, with a key path of its own) asks it first too, so nothing in the family lock can hold the adhan. */
    private boolean adhanTakes(KeyEvent e) {
        boolean adhan = NowPlaying.adhanLive();
        int stop = adhanKeys.decide(e.getKeyCode(), e.getAction(), e.getRepeatCount(), adhan, adhanBar.getVisibility() == View.VISIBLE,
                getCurrentFocus() == adhanBar, adhan && NowPlaying.adhanStopKey(this));
        if (stop == AdhanKeys.STOP) NowPlaying.stopAdhan(this);
        return stop != AdhanKeys.PASS;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // while the adhan plays: OK on its bar stops it, and with «إيقاف الأذان بأي زر» any key does; that press is consumed whole
        if (adhanTakes(e)) return true;
        // Menu key on the remote opens the Inputs dropdown.
        if (e.getKeyCode() == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP && !pills.isEmpty()) {
            showInputs(pills.get(0), ParentalPolicy.MENU_KEY);
            return true;
        }
        // On the «يُتلى الآن» card ◀ is the previous surah and ▶ the next (a station has none: ◀ goes back to the pills as on any pill)
        if (e.getAction() == KeyEvent.ACTION_DOWN && nowCard != null && getCurrentFocus() == nowCard
                && (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT || e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            NowPlaying.State s = NowPlaying.read(this);
            if (s.show && !s.radio) {
                if (e.getRepeatCount() == 0) {
                    if (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT) NowPlaying.prev(this); else NowPlaying.next(this);
                }
                return true;
            }
        }
        // In a right to left layout Android looks for the focus the other way round, so the rows are
        // walked by hand here: «right» always moves the highlight to the right. A held key too: its repeats
        // used to reach Android's own search, which went the other way and raced the opening pill.
        if (e.getAction() == KeyEvent.ACTION_DOWN
                && (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT || e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            if (e.getRepeatCount() > 0 && sideRow()) {
                if (e.getEventTime() - lastSideMove < SIDE_REPEAT_MS) return true;   // a step at a time while held
            }
            if (moveFocusSideways(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                lastSideMove = e.getEventTime();
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    /** While a left/right key is held, the highlight moves one item per this many ms (the pill opens in 150). */
    private static final long SIDE_REPEAT_MS = 120;
    private long lastSideMove;

    /** Whether the focus is in a row that moveFocusSideways walks (a pill or an app tile). */
    private boolean sideRow() {
        View focused = getCurrentFocus();
        ViewParent p = focused == null ? null : focused.getParent();
        if (isHorizontalRow(p)) return true;
        return p instanceof ViewGroup && isHorizontalRow(((ViewGroup) p).getParent());
    }

    private static boolean isHorizontalRow(ViewParent p) {
        return p instanceof LinearLayout && ((LinearLayout) p).getOrientation() == LinearLayout.HORIZONTAL;
    }

    /** Moves the focus to the next item of the row, on the right (true) or on the left (false). */
    private boolean moveFocusSideways(boolean right) {
        View focused = getCurrentFocus();
        if (focused == null) return false;
        ViewGroup parent = focused.getParent() instanceof ViewGroup ? (ViewGroup) focused.getParent() : null;
        if (parent == null) return false;

        ViewGroup row = null;
        View item = null;
        if (parent instanceof LinearLayout && ((LinearLayout) parent).getOrientation() == LinearLayout.HORIZONTAL) {
            // the focus is on an item of the row itself (a pill)
            row = parent;
            item = focused;
        } else {
            // the app tiles: the focus is on the card inside its box, the row is above the box
            ViewGroup above = parent.getParent() instanceof ViewGroup ? (ViewGroup) parent.getParent() : null;
            if (above instanceof LinearLayout && ((LinearLayout) above).getOrientation() == LinearLayout.HORIZONTAL) {
                row = above;
                item = parent;
            }
        }
        if (row == null) return false;
        int index = row.indexOfChild(item);
        if (index < 0) return false;
        // the row reads from the right, so the item before the focused one is the one on the right
        int next = right ? index - 1 : index + 1;
        if (next < 0 || next >= row.getChildCount()) return true;   // the edge of the row: stay put
        View target = row.getChildAt(next);
        if (target == null || target.getVisibility() != View.VISIBLE) return true;
        View toFocus = target;
        if (target instanceof ViewGroup) {
            ViewGroup box = (ViewGroup) target;
            for (int i = 0; i < box.getChildCount(); i++) {
                View child = box.getChildAt(i);
                if (child.isFocusable() && child.getVisibility() == View.VISIBLE) {
                    toFocus = child;
                    break;
                }
            }
        }
        if (!toFocus.isFocusable() || toFocus.getVisibility() != View.VISIBLE) return true;
        toFocus.requestFocus();
        return true;
    }

    /** 16:9 container. */
    static final class AspectFrame extends FrameLayout {
        AspectFrame(Context c) { super(c); }
        @Override protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY));
        }
    }

    /** ScrollView that never auto-scrolls to the focused child; the activity scrolls it by row. */
    static final class HomeScrollView extends android.widget.ScrollView {
        HomeScrollView(Context c) { super(c); }
        @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect r) { return 0; }
    }
}
