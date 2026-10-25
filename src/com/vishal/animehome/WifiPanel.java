package com.vishal.animehome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * iOS-style Wi-Fi dropdown under the status button: the current network, nearby networks
 * (saved ones first), and "More Wi-Fi settings" at the bottom. It is drawn inside the home
 * screen rather than a popup window, so Up from the top row reaches the button again and
 * pressing it folds the card away. Joining a network uses the legacy WifiManager calls,
 * which only work because the app targets SDK 28.
 */
final class WifiPanel {
    private static final long JOIN_TIMEOUT_MS = 20000;

    private final Activity a;
    private final float d;
    private final int accent;
    private final WifiManager wm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private static final int WIDTH_DP = 280;

    private final FrameLayout root;
    private final View anchor;
    /** The dropdown card while it is showing (or folding away); null when closed. */
    private LinearLayout card;
    private boolean closing;
    private LinearLayout list;
    private ScrollView scroll;
    private TextView hint;
    /** SSID being joined, with the network id we added (or -1 if it was already saved). */
    private String joining;
    private int addedId = -1;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (WifiManager.SUPPLICANT_STATE_CHANGED_ACTION.equals(i.getAction())
                && i.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, 0) == WifiManager.ERROR_AUTHENTICATING
                && joining != null) {
                joinFailed("Wrong password for " + joining);
                return;
            }
            refresh();
        }
    };

    WifiPanel(Activity a, FrameLayout root, View anchor, int accent) {
        this.a = a;
        this.root = root;
        this.anchor = anchor;
        this.d = a.getResources().getDisplayMetrics().density;
        this.accent = accent;
        this.wm = a.getApplicationContext().getSystemService(WifiManager.class);
    }

    private int dp(float v) { return Math.round(v * d); }

    int accent() { return accent; }

    boolean isOpen() { return card != null && !closing; }

    void toggle() {
        if (isOpen()) close(true);
        else open();
    }

    private void open() {
        if (card != null) {
            // Pressed again while it was folding away: finish the close first.
            card.animate().cancel();
            root.removeView(card);
            card = null;
            closing = false;
        }
        card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(8), dp(10), dp(8), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF0101822);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), 0x33FFFFFF);
        card.setBackground(bg);

        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(10), 0, dp(10), dp(4));
        head.addView(text("Wi-Fi", 15, Color.WHITE, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        hint = text("", 11, 0x99FFFFFF, false);
        head.addView(hint);
        card.addView(head);

        scroll = new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        card.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));

        card.addView(divider());
        TextView more = row(null, "More Wi-Fi settings", "›", null);
        // Last row: Down stays put instead of jumping to the app row underneath.
        more.setNextFocusDownId(more.getId());
        more.setOnClickListener(v -> openSettings());
        card.addView(more);

        // Hang the card under the button, right edges lined up.
        int[] rl = new int[2], al = new int[2];
        root.getLocationInWindow(rl);
        anchor.getLocationInWindow(al);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP | Gravity.END);
        lp.topMargin = al[1] - rl[1] + anchor.getHeight() + dp(8);
        lp.rightMargin = root.getWidth() - (al[0] - rl[0] + anchor.getWidth());
        root.addView(card, lp);

        IntentFilter f = new IntentFilter();
        f.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        f.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        f.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);
        f.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        a.registerReceiver(receiver, f);

        refresh();
        if (wm.isWifiEnabled()) {
            wm.startScan();
            hint.setText("Searching…");
        }

        // Unfold downward from the button; close() plays the same motion in reverse.
        card.setPivotX(dp(WIDTH_DP) - anchor.getWidth() / 2f);
        card.setPivotY(0);
        card.setScaleX(0.85f);
        card.setScaleY(0.3f);
        card.setAlpha(0f);
        card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220)
            .setInterpolator(new DecelerateInterpolator()).start();
        // Focus stays on the Wi-Fi button; Down moves into the list, OK again closes it.
    }

    void close(boolean animate) {
        if (card == null || closing) return;
        closing = true;
        ui.removeCallbacksAndMessages(null);
        try {
            a.unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
        }
        LinearLayout c = card;
        if (c.hasFocus()) anchor.requestFocus();
        Runnable done = () -> {
            root.removeView(c);
            if (card == c) {
                card = null;
                closing = false;
            }
        };
        if (!animate) {
            done.run();
            return;
        }
        c.animate().scaleX(0.85f).scaleY(0.3f).alpha(0f).setDuration(180)
            .setInterpolator(new AccelerateInterpolator()).withEndAction(done).start();
    }

    // ---------------------------------------------------------------- list

    private void refresh() {
        if (card == null || closing) return;
        int focusedIdx = indexOfFocus();
        list.removeAllViews();

        if (!wm.isWifiEnabled()) {
            hint.setText("");
            TextView on = row(null, "Turn Wi-Fi on", null, null);
            on.setOnClickListener(v -> {
                wm.setWifiEnabled(true);
                hint.setText("Turning on…");
            });
            list.addView(on);
            fitHeight();
            restoreFocus(focusedIdx);
            return;
        }

        WifiInfo info = wm.getConnectionInfo();
        String current = info != null && info.getSupplicantState() == SupplicantState.COMPLETED ? unquote(info.getSSID()) : null;
        if (current != null && current.equals(joining)) joinSucceeded();

        Map<String, Integer> saved = new HashMap<>();
        List<WifiConfiguration> configs = wm.getConfiguredNetworks();
        if (configs != null) for (WifiConfiguration c : configs) saved.put(unquote(c.SSID), c.networkId);

        // One entry per name, keeping the strongest access point.
        Map<String, ScanResult> best = new HashMap<>();
        for (ScanResult r : wm.getScanResults()) {
            if (TextUtils.isEmpty(r.SSID)) continue;
            ScanResult old = best.get(r.SSID);
            if (old == null || r.level > old.level) best.put(r.SSID, r);
        }
        List<ScanResult> mine = new ArrayList<>(), other = new ArrayList<>();
        for (ScanResult r : best.values()) {
            if (r.SSID.equals(current)) continue;
            (saved.containsKey(r.SSID) ? mine : other).add(r);
        }
        Collections.sort(mine, (x, y) -> y.level - x.level);
        Collections.sort(other, (x, y) -> y.level - x.level);

        if (current != null) {
            ScanResult r = best.get(current);
            int level = WifiManager.calculateSignalLevel(r != null ? r.level : info.getRssi(), 5);
            TextView row = row("✓", current, null, signal(level, r == null || secured(r)));
            row.setOnClickListener(v -> openSettings());
            list.addView(row);
        }
        if (!mine.isEmpty()) {
            list.addView(section("MY NETWORKS"));
            for (ScanResult r : mine) list.addView(networkRow(r, saved.get(r.SSID)));
        }
        if (!other.isEmpty()) {
            list.addView(section("OTHER NETWORKS"));
            for (ScanResult r : other) list.addView(networkRow(r, null));
        }
        if (current == null && best.isEmpty()) list.addView(section("No networks found yet"));
        if (joining == null) hint.setText(best.isEmpty() ? "Searching…" : "");
        fitHeight();
        restoreFocus(focusedIdx);
    }

    private TextView networkRow(ScanResult r, Integer savedId) {
        boolean lock = secured(r);
        String sub = r.SSID.equals(joining) ? "Joining…" : null;
        TextView row = row(null, r.SSID, sub, signal(WifiManager.calculateSignalLevel(r.level, 5), lock));
        row.setOnClickListener(v -> {
            if (savedId != null) join(r.SSID, savedId, false);
            else if (!lock) join(r.SSID, addConfig(r, null), true);
            else if (r.capabilities.contains("WEP")) openSettings();
            else askPassword(r);
        });
        return row;
    }

    // ---------------------------------------------------------------- joining

    private void askPassword(ScanResult r) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        EditText pw = new EditText(a);
        pw.setSingleLine(true);
        pw.setHint("Password");
        pw.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pw.setImeOptions(EditorInfo.IME_ACTION_DONE);
        box.addView(pw);
        CheckBox showPw = new CheckBox(a);
        showPw.setText("Show password");
        showPw.setOnCheckedChangeListener((b, on) -> {
            pw.setInputType(InputType.TYPE_CLASS_TEXT
                | (on ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            pw.setSelection(pw.length());
        });
        box.addView(showPw);

        AlertDialog dlg = new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Enter the password for “" + r.SSID + "”")
            .setView(box)
            .setPositiveButton("Join", (di, w) -> tryJoin(r, pw.getText().toString()))
            .setNegativeButton("Cancel", null)
            .create();
        pw.setOnEditorActionListener((v, id, e) -> {
            if (id != EditorInfo.IME_ACTION_DONE) return false;
            dlg.dismiss();
            tryJoin(r, pw.getText().toString());
            return true;
        });
        dlg.setOnShowListener(di -> pw.requestFocus());
        dlg.show();
    }

    private void tryJoin(ScanResult r, String password) {
        if (password.length() < 8) {
            Toast.makeText(a, "Wi-Fi passwords are at least 8 characters", Toast.LENGTH_SHORT).show();
            return;
        }
        join(r.SSID, addConfig(r, password), true);
    }

    private int addConfig(ScanResult r, String password) {
        WifiConfiguration c = new WifiConfiguration();
        c.SSID = "\"" + r.SSID + "\"";
        if (password == null) {
            c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        } else {
            boolean wpa3Only = r.capabilities.contains("SAE") && !r.capabilities.contains("PSK");
            c.allowedKeyManagement.set(wpa3Only && Build.VERSION.SDK_INT >= 29
                ? WifiConfiguration.KeyMgmt.SAE : WifiConfiguration.KeyMgmt.WPA_PSK);
            c.preSharedKey = "\"" + password + "\"";
        }
        return wm.addNetwork(c);
    }

    private void join(String ssid, int networkId, boolean added) {
        if (networkId < 0 || !wm.enableNetwork(networkId, true)) {
            Toast.makeText(a, "Couldn't join " + ssid, Toast.LENGTH_SHORT).show();
            openSettings();
            return;
        }
        wm.reconnect();
        joining = ssid;
        addedId = added ? networkId : -1;
        hint.setText("Joining " + ssid + "…");
        ui.removeCallbacksAndMessages(null);
        ui.postDelayed(() -> joinFailed("Couldn't join " + ssid), JOIN_TIMEOUT_MS);
        refresh();
    }

    private void joinSucceeded() {
        joining = null;
        addedId = -1;
        ui.removeCallbacksAndMessages(null);
        hint.setText("");
    }

    private void joinFailed(String message) {
        // Forget a network we just added so a wrong password isn't kept around.
        if (addedId >= 0) wm.removeNetwork(addedId);
        joining = null;
        addedId = -1;
        ui.removeCallbacksAndMessages(null);
        hint.setText("");
        Toast.makeText(a, message, Toast.LENGTH_LONG).show();
        refresh();
    }

    private void openSettings() {
        close(false);
        try {
            a.startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- views

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** A focusable row: optional check mark, name (+ grey note), and a trailing icon or chevron. */
    private TextView row(String check, String name, String note, Drawable end) {
        TextView t = new TextView(a);
        t.setText((check != null ? check + "  " : "      ") + name + (note != null ? "   " + note : ""));
        t.setId(View.generateViewId());
        // Left/Right stay inside the card rather than wandering off to the home screen.
        t.setNextFocusLeftId(t.getId());
        t.setNextFocusRightId(t.getId());
        t.setTextSize(13);
        t.setTextColor(Color.WHITE);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(10), dp(7), dp(10), dp(7));
        t.setFocusable(true);
        t.setClickable(true);
        if (end != null) {
            t.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, end, null);
            t.setCompoundDrawablePadding(dp(8));
        }
        t.setBackground(rowBg(false));
        t.setOnFocusChangeListener((v, has) -> {
            v.setBackground(rowBg(has));
            ((TextView) v).setTextColor(has ? Color.BLACK : Color.WHITE);
            Drawable[] ds = ((TextView) v).getCompoundDrawablesRelative();
            if (ds[2] != null) ds[2].setColorFilter(has ? Color.BLACK : Color.WHITE, PorterDuff.Mode.SRC_IN);
        });
        if (end != null) end.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
        return t;
    }

    private GradientDrawable rowBg(boolean has) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(has ? accent : Color.TRANSPARENT);
        g.setCornerRadius(dp(10));
        return g;
    }

    private TextView section(String s) {
        TextView t = text(s, 10, 0x80FFFFFF, true);
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(10), dp(8), dp(10), dp(2));
        return t;
    }

    private View divider() {
        View v = new View(a);
        v.setBackgroundColor(0x26FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.setMargins(dp(10), dp(4), dp(10), dp(4));
        v.setLayoutParams(lp);
        return v;
    }

    /** Lock (if secured) followed by the signal bars, drawn as one trailing icon. */
    private Drawable signal(int level, boolean lock) {
        Drawable bars = null;
        try (InputStream in = a.getAssets().open("status/wifi_" + level + ".png")) {
            bars = new android.graphics.drawable.BitmapDrawable(a.getResources(), BitmapFactory.decodeStream(in));
        } catch (Exception ignored) {
        }
        return new SignalIcon(bars, lock, dp(15), dp(10), dp(5));
    }

    private static boolean secured(ScanResult r) {
        String c = r.capabilities;
        return c.contains("WEP") || c.contains("PSK") || c.contains("SAE") || c.contains("EAP");
    }

    private static String unquote(String s) {
        if (s == null || "<unknown ssid>".equals(s)) return null;
        return s.length() > 1 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
    }

    /** Fits the list to its rows, capped so the dropdown never runs off the bottom of the screen. */
    private void fitHeight() {
        list.measure(View.MeasureSpec.makeMeasureSpec(dp(WIDTH_DP - 16), View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        ViewGroup.LayoutParams lp = scroll.getLayoutParams();
        int h = Math.min(list.getMeasuredHeight(), dp(220));
        if (lp.height != h) {
            lp.height = h;
            scroll.setLayoutParams(lp);
        }
    }

    private int firstFocusable() {
        for (int i = 0; i < list.getChildCount(); i++) if (list.getChildAt(i).isFocusable()) return i;
        return 0;
    }

    private int indexOfFocus() {
        for (int i = 0; i < list.getChildCount(); i++) if (list.getChildAt(i).isFocused()) return i;
        return -1;
    }

    private void restoreFocus(int idx) {
        if (idx < 0 || list.getChildCount() == 0) return;
        int i = Math.min(idx, list.getChildCount() - 1);
        while (i > 0 && !list.getChildAt(i).isFocusable()) i--;
        if (!list.getChildAt(i).isFocusable()) i = firstFocusable();
        list.getChildAt(i).requestFocus();
    }

    /** Small padlock + signal bars, tinted together through the text view's color filter. */
    private static final class SignalIcon extends Drawable {
        private final Drawable bars;
        private final boolean lock;
        private final int size, lockW, gap;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        SignalIcon(Drawable bars, boolean lock, int size, int lockW, int gap) {
            this.bars = bars;
            this.lock = lock;
            this.size = size;
            this.lockW = lockW;
            this.gap = gap;
            p.setColor(Color.WHITE);
        }

        @Override public int getIntrinsicWidth() { return size + (lock ? lockW + gap : 0); }
        @Override public int getIntrinsicHeight() { return size; }

        @Override public void draw(Canvas c) {
            int x = getBounds().left, y = getBounds().top;
            if (lock) {
                float w = lockW, top = y + size * 0.42f, bottom = y + size * 0.92f;
                p.setStyle(Paint.Style.FILL);
                c.drawRoundRect(new RectF(x, top, x + w, bottom), w * 0.18f, w * 0.18f, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(w * 0.16f);
                float inset = w * 0.22f;
                c.drawArc(new RectF(x + inset, y + size * 0.12f, x + w - inset, top + w * 0.45f), 180, 180, false, p);
                c.drawLine(x + inset, top - w * 0.02f, x + inset, top + w * 0.2f, p);
                c.drawLine(x + w - inset, top - w * 0.02f, x + w - inset, top + w * 0.2f, p);
                x += lockW + gap;
            }
            if (bars != null) {
                bars.setBounds(x, y, x + size, y + size);
                bars.draw(c);
            }
        }

        @Override public void setAlpha(int alpha) { p.setAlpha(alpha); if (bars != null) bars.setAlpha(alpha); }

        @Override public void setColorFilter(ColorFilter cf) {
            p.setColorFilter(cf);
            if (bars != null) bars.setColorFilter(cf);
            invalidateSelf();
        }

        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
