package com.vishal.animehome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.media.tv.TvContract;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Home extends Activity {
    private static final String AUTO = "auto";

    private float d;
    private SharedPreferences prefs;
    private Theme theme;

    private ImageView bgA, bgB;
    private boolean aVisible = true;
    private TextView themeLabel, quoteView, focusName;
    private LinearLayout chipsRow, appsRow, utilRow;
    private HorizontalScrollView appsScroll;
    private FxView fx;
    private Shelf shelf;
    private View shelfScrim, shelfSource;
    private Suggestions suggestions;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable pendingShelf;
    private FrameLayout wifiBtn;
    private ImageView wifiIcon;
    private String wifiStatus = "Not connected";
    private ConnectivityManager.NetworkCallback netCallback;

    private final List<App> apps = new ArrayList<>();
    private boolean appsDirty = true;
    private boolean firstLoad = true;

    /** Default order until the user rearranges: streaming first, TV utilities last. */
    private static final String[] PREFERRED = {
        "com.google.android.youtube.tv", "com.netflix.ninja", "com.amazon.amazonvideo.livingroom",
        "in.startv.hotstar", "com.sonyliv", "com.graymatrix.did", "com.fancode.tv",
        "com.mxtech.videoplayer.television", "com.android.vending", "com.google.android.play.games",
    };

    private static final class App {
        String key, label;
        ComponentName cn;
        Drawable icon;
        /** White line-art icon from assets/icons (Lawnicons), tinted with the theme color. */
        Bitmap line;
    }

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            appsDirty = true;
            if (hasWindowFocus()) loadApps(null);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        d = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        setContentView(buildUi());
        suggestions = new Suggestions(this, shelf.posterW, shelf.posterH);
        suggestions.refresh(this::onSuggestionsReady);
        watchNetwork();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packageReceiver, f, Context.RECEIVER_EXPORTED);

        themeFromIntent(getIntent());
        applyTheme(currentTheme(), true);
        buildUtilRow();
    }

    /** Lets a theme be picked when launching, e.g. `am start -n ... --es theme ichigo` (or "auto"). */
    private boolean themeFromIntent(Intent i) {
        if (i != null) FxView.slow = i.getFloatExtra("fxslow", 1f);
        String id = i == null ? null : i.getStringExtra("theme");
        if (id == null) return false;
        prefs.edit().putString("theme", id).apply();
        return true;
    }

    @Override protected void onDestroy() {
        unregisterReceiver(packageReceiver);
        if (netCallback != null) getSystemService(ConnectivityManager.class).unregisterNetworkCallback(netCallback);
        super.onDestroy();
    }

    @Override protected void onResume() {
        super.onResume();
        if (appsDirty) loadApps(null);
        suggestions.refresh(this::onSuggestionsReady);
        // Auto mode follows the day, so pick up a new day's theme when coming back home.
        if (AUTO.equals(prefs.getString("theme", AUTO)) && currentTheme() != theme) applyTheme(currentTheme(), true);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (themeFromIntent(intent)) {
            applyTheme(currentTheme(), true);
            return;
        }
        // Home pressed while already home: jump back to the first app.
        appsScroll.smoothScrollTo(0, 0);
        if (appsRow.getChildCount() > 0) appsRow.getChildAt(0).requestFocus();
    }

    @Override public void onBackPressed() {
        // From the suggestions shelf, Back returns to the app; otherwise home has nowhere to go back to.
        if (shelf.contains(getCurrentFocus()) && shelfSource != null) shelfSource.requestFocus();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            View sel = chipsRow.findViewWithTag(prefs.getString("theme", AUTO));
            if (sel != null) sel.requestFocus();
            return true;
        }
        return super.onKeyDown(keyCode, e);
    }

    // ---------------------------------------------------------------- layout

    private int dp(float v) { return Math.round(v * d); }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setClipChildren(false);

        bgA = wallpaperView();
        bgB = wallpaperView();
        bgB.setAlpha(0f);
        root.addView(bgA, match());
        root.addView(bgB, match());

        View leftScrim = new View(this);
        leftScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            new int[] {0xD8000000, 0x80000000, 0x00000000}));
        root.addView(leftScrim, new FrameLayout.LayoutParams(dp(560), dp(300), Gravity.TOP | Gravity.START));

        View bottomScrim = new View(this);
        bottomScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
            new int[] {0xF0000000, 0x99000000, 0x00000000}));
        root.addView(bottomScrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(250), Gravity.BOTTOM));

        // Extra shade that fades in behind the suggestions shelf.
        shelfScrim = new View(this);
        shelfScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
            new int[] {0xCC000000, 0x99000000, 0x00000000}));
        shelfScrim.setAlpha(0f);
        root.addView(shelfScrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(430), Gravity.BOTTOM));

        // Top left: clock, date, theme name and quote.
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextSize(58);
        clock.setTextColor(Color.WHITE);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clock.setShadowLayer(dp(6), 0, dp(2), 0xAA000000);
        info.addView(clock);
        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        date.setTextSize(17);
        date.setTextColor(0xDDFFFFFF);
        date.setShadowLayer(dp(4), 0, dp(1), 0xAA000000);
        info.addView(date);
        themeLabel = new TextView(this);
        themeLabel.setTextSize(15);
        themeLabel.setTypeface(Typeface.DEFAULT_BOLD);
        themeLabel.setLetterSpacing(0.22f);
        themeLabel.setShadowLayer(dp(4), 0, dp(1), 0xCC000000);
        themeLabel.setPadding(0, dp(18), 0, dp(4));
        info.addView(themeLabel);
        quoteView = new TextView(this);
        quoteView.setTextSize(15);
        quoteView.setTextColor(0xE6FFFFFF);
        quoteView.setTypeface(Typeface.create(Typeface.SERIF, Typeface.ITALIC));
        quoteView.setMaxWidth(dp(420));
        quoteView.setShadowLayer(dp(4), 0, dp(1), 0xCC000000);
        info.addView(quoteView);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        ip.setMargins(dp(48), dp(28), 0, 0);
        root.addView(info, ip);

        // Top right: theme chips.
        chipsRow = new LinearLayout(this);
        chipsRow.setClipChildren(false);
        chipsRow.setClipToPadding(false);
        chipsRow.setPadding(dp(8), dp(8), dp(8), dp(8));
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        cp.setMargins(0, dp(24), dp(40), 0);
        root.addView(chipsRow, cp);
        buildChips();

        // Top right: Wi-Fi status button (opens network settings).
        LinearLayout status = new LinearLayout(this);
        status.setClipChildren(false);
        status.setClipToPadding(false);
        status.setPadding(dp(8), dp(8), dp(8), dp(8));
        FrameLayout.LayoutParams stp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        stp.setMargins(0, dp(24), dp(40), 0);
        root.addView(status, stp);
        wifiBtn = new FrameLayout(this);
        wifiBtn.setFocusable(true);
        wifiIcon = new ImageView(this);
        wifiIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        wifiBtn.addView(wifiIcon, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        status.addView(wifiBtn, new LinearLayout.LayoutParams(dp(46), dp(46)));
        updateWifi(false, false, Integer.MIN_VALUE);
        styleWifi(false);
        wifiBtn.setOnFocusChangeListener((v, has) -> {
            styleWifi(has);
            v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).setDuration(160).start();
            if (has) {
                hideShelf();
                focusName.setText("Wi-Fi  ·  " + wifiStatus);
            }
        });
        wifiBtn.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException ignored) {
            }
        });

        // Bottom: focused app name, app row, inputs + settings.
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setClipChildren(false);
        shelf = new Shelf(this, d);
        LinearLayout.LayoutParams shp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
        shp.bottomMargin = dp(14);
        bottom.addView(shelf, shp);
        focusName = new TextView(this);
        focusName.setTextSize(18);
        focusName.setTypeface(Typeface.DEFAULT_BOLD);
        focusName.setTextColor(Color.WHITE);
        focusName.setPadding(dp(48), 0, 0, 0);
        focusName.setShadowLayer(dp(4), 0, dp(1), 0xCC000000);
        bottom.addView(focusName);
        appsScroll = new HorizontalScrollView(this);
        appsScroll.setHorizontalScrollBarEnabled(false);
        appsScroll.setClipToPadding(false);
        appsScroll.setPadding(dp(48), dp(6), dp(48), dp(6));
        appsScroll.setOnScrollChangeListener((v, x, y, ox, oy) -> fx.invalidate());
        appsRow = new LinearLayout(this);
        appsRow.setClipChildren(false);
        // Room inside the row so the enlarged, glowing selected button isn't clipped.
        appsRow.setPadding(dp(8), dp(10), dp(8), dp(10));
        appsRow.setClipToPadding(false);
        appsScroll.addView(appsRow);
        bottom.addView(appsScroll);
        utilRow = new LinearLayout(this);
        utilRow.setClipChildren(false);
        utilRow.setClipToPadding(false);
        utilRow.setPadding(dp(48), dp(2), dp(48), dp(4));
        bottom.addView(utilRow);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        bp.bottomMargin = dp(22);
        root.addView(bottom, bp);

        fx = new FxView(this);
        root.addView(fx, match());
        return root;
    }

    private ImageView wallpaperView() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusDp * d);
        return g;
    }

    private GradientDrawable border(int color, float radiusDp) {
        GradientDrawable g = rounded(Color.TRANSPARENT, radiusDp);
        g.setStroke(dp(2), color);
        return g;
    }

    // ---------------------------------------------------------------- themes

    private static int dayOfYear() { return Calendar.getInstance().get(Calendar.DAY_OF_YEAR); }

    private Theme currentTheme() {
        String id = prefs.getString("theme", AUTO);
        for (Theme t : Theme.ALL) if (t.id.equals(id)) return t;
        return Theme.ALL[dayOfYear() % Theme.ALL.length];
    }

    private void applyTheme(Theme t, boolean animate) {
        theme = t;
        int day = dayOfYear();
        String wall = t.wallpapers[(day / Theme.ALL.length) % t.wallpapers.length];
        themeLabel.setText(t.symbol + "   " + t.name + "  ·  " + t.series.toUpperCase());
        themeLabel.setTextColor(t.accent);
        quoteView.setText(t.quotes[day % t.quotes.length]);
        refreshChips();
        loadWallpaper(wall);
        if (animate) {
            for (View v : new View[] {themeLabel, quoteView}) {
                v.setAlpha(0f);
                v.setTranslationX(-dp(40));
                v.animate().alpha(1f).translationX(0f).setDuration(450)
                    .setStartDelay(v == quoteView ? 140 : 0).setInterpolator(new DecelerateInterpolator()).start();
            }
        }
        View f = getCurrentFocus();
        if (f != null && f.getParent() == appsRow) focusTile(f, true);
    }

    private void loadWallpaper(String name) {
        new Thread(() -> {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bmp = null;
            try (InputStream in = getAssets().open("wallpapers/" + name + ".jpg")) {
                bmp = BitmapFactory.decodeStream(in, null, o);
            } catch (Exception ignored) {
            }
            Bitmap result = bmp;
            runOnUiThread(() -> crossfadeTo(result));
        }).start();
    }

    private void crossfadeTo(Bitmap bmp) {
        if (bmp == null || isDestroyed()) return;
        ImageView show = aVisible ? bgB : bgA, hide = aVisible ? bgA : bgB;
        aVisible = !aVisible;
        show.setImageBitmap(bmp);
        // Move the incoming wallpaper above the outgoing one, still below the scrims, text and fx.
        ViewGroup root = (ViewGroup) show.getParent();
        root.removeView(show);
        root.addView(show, 1);
        show.setAlpha(0f);
        show.animate().alpha(1f).setDuration(600).withEndAction(() -> {
            hide.setAlpha(0f);
            hide.setImageDrawable(null);
        }).start();
    }

    private void buildChips() {
        // Nothing to switch between with a single theme.
        if (Theme.ALL.length < 2) return;
        addChip(AUTO, "↻", 0xFF2A2A2A, Color.WHITE);
        for (Theme t : Theme.ALL) addChip(t.id, t.symbol, t.chipBg, t.accent);
    }

    private void addChip(String id, String symbol, int bg, int fg) {
        TextView c = new TextView(this);
        c.setTag(id);
        c.setText(symbol);
        c.setTextSize(19);
        c.setTextColor(fg);
        c.setGravity(Gravity.CENTER);
        c.setFocusable(true);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(bg);
        c.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(42), dp(42));
        lp.setMargins(dp(5), 0, dp(5), 0);
        c.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.18f : 1f).scaleY(has ? 1.18f : 1f).setDuration(150).start();
            if (has) focusName.setText(AUTO.equals(id) ? "Auto: a new character every day" : themeById(id).name);
            refreshChips();
        });
        c.setOnClickListener(v -> {
            prefs.edit().putString("theme", id).apply();
            applyTheme(currentTheme(), true);
            if (AUTO.equals(id)) Toast.makeText(this, "Auto: theme changes every day", Toast.LENGTH_SHORT).show();
        });
        chipsRow.addView(c, lp);
    }

    private Theme themeById(String id) {
        for (Theme t : Theme.ALL) if (t.id.equals(id)) return t;
        return Theme.ALL[0];
    }

    private void refreshChips() {
        String sel = prefs.getString("theme", AUTO);
        for (int i = 0; i < chipsRow.getChildCount(); i++) {
            TextView c = (TextView) chipsRow.getChildAt(i);
            GradientDrawable g = (GradientDrawable) c.getBackground();
            boolean selected = sel.equals(c.getTag());
            g.setStroke(c.isFocused() ? dp(3) : selected ? dp(2) : 0, c.isFocused() ? Color.WHITE : theme == null ? Color.WHITE : theme.accent);
            c.setAlpha(selected || c.isFocused() ? 1f : 0.75f);
        }
    }

    // ---------------------------------------------------------------- apps

    private void loadApps(String focusKey) {
        appsDirty = false;
        PackageManager pm = getPackageManager();
        new Thread(() -> {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            List<App> list = new ArrayList<>();
            Set<String> hidden = prefs.getStringSet("hidden", new HashSet<>());
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                ActivityInfo ai = ri.activityInfo;
                if (ai.packageName.equals(getPackageName())) continue;
                App a = new App();
                a.cn = new ComponentName(ai.packageName, ai.name);
                a.key = a.cn.flattenToShortString();
                if (hidden.contains(a.key)) continue;
                a.label = String.valueOf(ri.loadLabel(pm));
                a.icon = ri.loadIcon(pm);
                try (InputStream in = getAssets().open("icons/" + ai.packageName + ".png")) {
                    a.line = BitmapFactory.decodeStream(in);
                } catch (Exception ignored) {
                }
                list.add(a);
            }
            List<String> order = savedOrder();
            list.sort((x, y) -> {
                int ix = rank(order, x), iy = rank(order, y);
                return ix != iy ? Integer.compare(ix, iy) : x.label.compareToIgnoreCase(y.label);
            });
            runOnUiThread(() -> showApps(list, focusKey));
        }).start();
    }

    private static int rank(List<String> order, App a) {
        int i = order.indexOf(a.key);
        if (i >= 0) return i;
        int p = Arrays.asList(PREFERRED).indexOf(a.cn.getPackageName());
        return p >= 0 ? 10000 + p : Integer.MAX_VALUE;
    }

    private List<String> savedOrder() {
        String s = prefs.getString("order", "");
        return s.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(s.split(",")));
    }

    private void saveOrder() {
        List<String> keys = new ArrayList<>();
        for (App a : apps) keys.add(a.key);
        prefs.edit().putString("order", TextUtils.join(",", keys)).apply();
    }

    private void showApps(List<App> list, String focusKey) {
        if (isDestroyed()) return;
        View focused = getCurrentFocus();
        boolean hadAppFocus = focused != null && focused.getParent() == appsRow;
        String keepKey = focusKey != null ? focusKey : hadAppFocus ? (String) focused.getTag() : null;
        fx.clear(focused);
        apps.clear();
        apps.addAll(list);
        appsRow.removeAllViews();
        View toFocus = null;
        for (App a : apps) {
            View tile = appTile(a);
            appsRow.addView(tile);
            if (a.key.equals(keepKey)) toFocus = tile;
        }
        if (toFocus == null && (keepKey != null || firstLoad || getCurrentFocus() == null) && appsRow.getChildCount() > 0) {
            toFocus = appsRow.getChildAt(0);
        }
        if (toFocus != null) toFocus.requestFocus();
        firstLoad = false;
    }

    private View appTile(App a) {
        FrameLayout tile = new FrameLayout(this);
        tile.setTag(a.key);
        tile.setId(View.generateViewId());
        tile.setFocusable(true);
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int size;
        if (a.line != null) {
            img.setImageBitmap(a.line);
            size = dp(30);
        } else {
            img.setImageDrawable(a.icon);
            size = dp(38);
        }
        tile.addView(img, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
        styleTile(tile, img, a, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(64), dp(64));
        lp.setMargins(dp(10), 0, dp(10), 0);
        tile.setLayoutParams(lp);
        tile.setOnFocusChangeListener((v, has) -> {
            styleTile(tile, img, a, has);
            focusTile(v, has);
            if (has) {
                focusName.setText(a.label);
                keepInView(v);
                scheduleShelf(a, v);
            }
        });
        tile.setOnClickListener(v -> launch(a));
        tile.setOnLongClickListener(v -> {
            appMenu(a);
            return true;
        });
        return tile;
    }

    private void focusTile(View v, boolean has) {
        v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).setDuration(160).start();
        if (has && theme.fx != FxView.NONE) fx.play(theme.fx, v, theme.accent);
        else fx.clear(v);
    }

    /** Round glass button: white line icon normally, theme-colored icon and glowing ring when selected. */
    private void styleTile(FrameLayout tile, ImageView img, App a, boolean has) {
        int accent = theme.accent;
        tile.setBackground(roundGlass(has));
        if (a.line != null) {
            img.setColorFilter(has ? accent : 0xD9FFFFFF, PorterDuff.Mode.SRC_IN);
        } else if (has) {
            img.clearColorFilter();
        } else {
            ColorMatrix gray = new ColorMatrix();
            gray.setSaturation(0f);
            img.setColorFilter(new ColorMatrixColorFilter(gray));
        }
        img.setAlpha(has ? 1f : 0.85f);
    }

    /**
     * The scroll view only scrolls until a focused tile touches the screen edge, which cuts off the
     * enlarged tile's glow at the ends of the row. Keep it inside the row's side padding instead.
     */
    private void keepInView(View tile) {
        int left = appsRow.getLeft() + tile.getLeft() - dp(14);
        int right = appsRow.getLeft() + tile.getRight() + dp(14);
        int sx = appsScroll.getScrollX();
        int min = right - (appsScroll.getWidth() - appsScroll.getPaddingRight());
        int max = left - appsScroll.getPaddingLeft();
        if (sx < min) appsScroll.smoothScrollTo(min, 0);
        else if (sx > max) appsScroll.smoothScrollTo(Math.max(0, max), 0);
    }

    // ---------------------------------------------------------------- suggestions shelf

    /** Shows the app's top 5 after a short pause, so scrolling quickly past apps doesn't flicker. */
    private void scheduleShelf(App a, View tile) {
        if (pendingShelf != null) ui.removeCallbacks(pendingShelf);
        pendingShelf = () -> {
            List<Suggestions.Item> items = suggestions.get(a.cn.getPackageName());
            if (items.isEmpty()) {
                hideShelf();
                return;
            }
            shelfSource = tile;
            shelf.show(a.key, a.label, items, theme.accent, tile.getId(), it -> openSuggestion(it, a));
            shelfScrim.animate().alpha(1f).setDuration(220).start();
            themeLabel.animate().alpha(0f).setDuration(180).start();
            quoteView.animate().alpha(0f).setDuration(180).start();
        };
        ui.postDelayed(pendingShelf, shelf.getAlpha() > 0f ? 110 : 170);
    }

    private void hideShelf() {
        if (pendingShelf != null) ui.removeCallbacks(pendingShelf);
        shelf.hide();
        shelfScrim.animate().alpha(0f).setDuration(180).start();
        themeLabel.animate().alpha(1f).setDuration(220).start();
        quoteView.animate().alpha(1f).setDuration(220).start();
    }

    private void onSuggestionsReady() {
        // New data arrived: rebuild the shelf for whichever app is selected.
        View f = getCurrentFocus();
        if (f == null || f.getParent() != appsRow) return;
        for (App a : apps) {
            if (a.key.equals(f.getTag())) {
                shelf.hide();
                scheduleShelf(a, f);
            }
        }
    }

    /** Opens the title inside its app. Deep links come from the network, so they may only target that app. */
    private void openSuggestion(Suggestions.Item it, App a) {
        String pkg = a.cn.getPackageName();
        if (it.deeplink != null) {
            try {
                Intent i = Intent.parseUri(it.deeplink, Intent.URI_INTENT_SCHEME);
                i.setSelector(null);
                i.setComponent(null);
                i.setPackage(pkg);
                i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return;
            } catch (Exception ignored) {
            }
        }
        if (it.web != null) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(it.web)).setPackage(pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (Exception ignored) {
            }
        }
        launch(a);
    }

    /** Dark glass circle with a faint rim; selected adds a theme-colored ring and soft halo. */
    private Drawable roundGlass(boolean has) {
        int accent = theme == null ? 0xFF4FC3F7 : theme.accent;
        GradientDrawable glass = new GradientDrawable();
        glass.setShape(GradientDrawable.OVAL);
        glass.setColor(has ? 0xD9081420 : 0x8C000000);
        glass.setStroke(dp(has ? 2 : 1), has ? accent : 0x40FFFFFF);
        if (!has) return glass;
        GradientDrawable glow = new GradientDrawable();
        glow.setShape(GradientDrawable.OVAL);
        // A filled halo stays inside the view bounds; a thick stroke would spill out and get clipped flat.
        glow.setColor((accent & 0x00FFFFFF) | 0x38000000);
        LayerDrawable ring = new LayerDrawable(new Drawable[] {glow, glass});
        ring.setLayerInset(1, dp(5), dp(5), dp(5), dp(5));
        return ring;
    }

    // ---------------------------------------------------------------- wi-fi status

    private void watchNetwork() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities nc) {
                boolean wifi = nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                boolean cable = nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
                int rssi = nc.getSignalStrength();
                runOnUiThread(() -> updateWifi(wifi, cable, rssi));
            }

            @Override public void onLost(Network n) {
                runOnUiThread(() -> updateWifi(false, false, Integer.MIN_VALUE));
            }
        };
        cm.registerDefaultNetworkCallback(netCallback);
    }

    private void updateWifi(boolean wifi, boolean cable, int rssi) {
        String icon;
        if (wifi) {
            int level = rssi == NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED ? 4 : WifiManager.calculateSignalLevel(rssi, 5);
            icon = "wifi_" + level;
            wifiStatus = "Connected  ·  " + new String[] {"very weak", "weak", "fair", "good", "excellent"}[level] + " signal";
        } else if (cable) {
            icon = "wifi_4";
            wifiStatus = "Connected by cable";
        } else {
            icon = "wifi_off";
            wifiStatus = "Not connected";
        }
        try (InputStream in = getAssets().open("status/" + icon + ".png")) {
            wifiIcon.setImageBitmap(BitmapFactory.decodeStream(in));
        } catch (Exception ignored) {
        }
        if (wifiBtn.isFocused()) focusName.setText("Wi-Fi  ·  " + wifiStatus);
    }

    private void styleWifi(boolean has) {
        wifiBtn.setBackground(roundGlass(has));
        wifiIcon.setColorFilter(has ? (theme == null ? 0xFF4FC3F7 : theme.accent) : 0xD9FFFFFF, PorterDuff.Mode.SRC_IN);
    }

    private void launch(App a) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            .setComponent(a.cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Can't open " + a.label, Toast.LENGTH_SHORT).show();
        }
    }

    private void appMenu(App a) {
        String[] items = {"Move to front", "Move left", "Move right", "Hide app", "Show hidden apps", "App info"};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(a.label)
            .setItems(items, (dlg, which) -> {
                int i = apps.indexOf(a);
                switch (which) {
                    case 0: apps.remove(i); apps.add(0, a); break;
                    case 1: if (i > 0) { apps.remove(i); apps.add(i - 1, a); } break;
                    case 2: if (i < apps.size() - 1) { apps.remove(i); apps.add(i + 1, a); } break;
                    case 3: {
                        Set<String> h = new HashSet<>(prefs.getStringSet("hidden", new HashSet<>()));
                        h.add(a.key);
                        prefs.edit().putStringSet("hidden", h).apply();
                        apps.remove(i);
                        break;
                    }
                    case 4: prefs.edit().remove("hidden").apply(); break;
                    case 5: {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", a.cn.getPackageName(), null)));
                        return;
                    }
                }
                saveOrder();
                loadApps(which == 3 ? null : a.key);
            })
            .show();
    }

    // ---------------------------------------------------------------- inputs + settings

    private void buildUtilRow() {
        TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        if (tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.getType() != TvInputInfo.TYPE_HDMI || info.getParentId() != null) continue;
                String label = String.valueOf(info.loadLabel(this));
                addPill("▶  " + label, v -> openInput(info, label));
            }
        }
        addPill("⚙  Settings", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException ignored) {
            }
        });
    }

    private void addPill(String text, View.OnClickListener click) {
        TextView p = new TextView(this);
        p.setText(text);
        p.setTextSize(13);
        p.setTextColor(Color.WHITE);
        p.setFocusable(true);
        p.setPadding(dp(14), dp(6), dp(14), dp(6));
        p.setBackground(rounded(0x99000000, 16));
        p.setOnFocusChangeListener((v, has) -> {
            v.setBackground(rounded(has ? theme.accent : 0x99000000, 16));
            ((TextView) v).setTextColor(has ? Color.BLACK : Color.WHITE);
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).setDuration(140).start();
            if (has) {
                focusName.setText(text.substring(3));
                hideShelf();
            }
        });
        p.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(8), 0, dp(6), 0);
        utilRow.addView(p, lp);
    }

    private void openInput(TvInputInfo info, String label) {
        // Mi Live TV only matches the passthrough URI when the channel MIME type is given.
        Intent i = new Intent(Intent.ACTION_VIEW)
            .setDataAndType(TvContract.buildChannelUriForPassthroughInput(info.getId()), TvContract.Channels.CONTENT_ITEM_TYPE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No app can show " + label + " (Mi Live TV is missing)", Toast.LENGTH_LONG).show();
        }
    }
}
