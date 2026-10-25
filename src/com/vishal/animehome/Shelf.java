package com.vishal.animehome;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * "Top 5" shelf shown above the app row: a header with a lightning-blue streak and five poster cards
 * that rise in one after another. The five cards are created once and rebound for each app.
 */
final class Shelf extends LinearLayout {
    interface OnPick { void pick(Suggestions.Item item); }

    private static final int CARDS = 5;
    private final float d;
    private final TextView header;
    private final View streak;
    private final LinearLayout row;
    private final LinearLayout[] cards = new LinearLayout[CARDS];
    private final FrameLayout[] frames = new FrameLayout[CARDS];
    private final ImageView[] posters = new ImageView[CARDS];
    private final TextView[] ranks = new TextView[CARDS], titles = new TextView[CARDS], metas = new TextView[CARDS];
    private final DecelerateInterpolator ease = new DecelerateInterpolator(2f);
    private String shownKey;
    private int accent = Color.CYAN;

    final int posterW, posterH;

    Shelf(Context c, float density) {
        super(c);
        d = density;
        posterW = dp(86);
        posterH = dp(122);
        setOrientation(VERTICAL);
        setClipChildren(false);
        setClipToPadding(false);
        setPadding(dp(48), 0, dp(48), dp(4));
        setAlpha(0f);

        header = new TextView(c);
        header.setTextSize(13);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setLetterSpacing(0.2f);
        header.setShadowLayer(dp(4), 0, dp(1), 0xCC000000);
        addView(header);

        streak = new View(c);
        LayoutParams sp = new LayoutParams(dp(220), dp(2));
        sp.setMargins(0, dp(5), 0, dp(10));
        streak.setPivotX(0f);
        addView(streak, sp);

        row = new LinearLayout(c);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        addView(row);

        for (int i = 0; i < CARDS; i++) buildCard(c, i);
    }

    private int dp(float v) { return Math.round(v * d); }

    private void buildCard(Context c, int i) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(VERTICAL);
        card.setClipChildren(false);
        card.setFocusable(false);

        FrameLayout frame = new FrameLayout(c);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF0B1622);
        bg.setCornerRadius(dp(10));
        frame.setBackground(bg);
        frame.setClipToOutline(true);
        ImageView poster = new ImageView(c);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(poster, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView rank = new TextView(c);
        rank.setText(String.valueOf(i + 1));
        rank.setTextSize(12);
        rank.setTypeface(Typeface.DEFAULT_BOLD);
        rank.setTextColor(Color.BLACK);
        rank.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP | Gravity.START);
        rp.setMargins(dp(6), dp(6), 0, 0);
        frame.addView(rank, rp);
        card.addView(frame, new LayoutParams(posterW, posterH));

        TextView title = new TextView(c);
        title.setTextSize(12);
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(dp(2), dp(6), dp(2), 0);
        title.setShadowLayer(dp(3), 0, dp(1), 0xCC000000);
        card.addView(title, new LayoutParams(posterW, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView meta = new TextView(c);
        meta.setTextSize(10);
        meta.setTextColor(0xB3FFFFFF);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        meta.setPadding(dp(2), dp(1), dp(2), 0);
        card.addView(meta, new LayoutParams(posterW, ViewGroup.LayoutParams.WRAP_CONTENT));

        card.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).setDuration(150).setInterpolator(ease).start();
            frame.setForeground(has ? ring() : null);
            title.setTextColor(has ? accent : Color.WHITE);
        });

        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(18), 0);
        row.addView(card, lp);
        cards[i] = card;
        frames[i] = frame;
        posters[i] = poster;
        ranks[i] = rank;
        titles[i] = title;
        metas[i] = meta;
    }

    private GradientDrawable ring() {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(10));
        g.setStroke(dp(2), accent);
        return g;
    }

    boolean isShowing(String key) { return key != null && key.equals(shownKey); }

    boolean contains(View v) {
        for (LinearLayout c : cards) if (c == v) return true;
        return false;
    }

    void show(String key, String appLabel, List<Suggestions.Item> items, int accent, int downId, OnPick onPick) {
        if (isShowing(key)) return;
        shownKey = key;
        this.accent = accent;
        header.setText("雷   TOP 5 ON " + appLabel.toUpperCase() + "  ·  INDIA");
        header.setTextColor(accent);
        streak.setBackgroundColor(accent);

        animate().cancel();
        animate().alpha(1f).setDuration(160).start();
        header.setAlpha(0f);
        header.setTranslationX(-dp(16));
        header.animate().alpha(1f).translationX(0f).setDuration(240).setInterpolator(ease).start();
        streak.setScaleX(0f);
        streak.animate().scaleX(1f).setDuration(320).setStartDelay(60).setInterpolator(ease).start();

        for (int i = 0; i < CARDS; i++) {
            LinearLayout card = cards[i];
            card.animate().cancel();
            if (i >= items.size()) {
                card.setVisibility(GONE);
                card.setFocusable(false);
                continue;
            }
            Suggestions.Item it = items.get(i);
            card.setVisibility(VISIBLE);
            card.setFocusable(true);
            card.setNextFocusDownId(downId);
            posters[i].setImageBitmap(it.poster);
            GradientDrawable badge = new GradientDrawable();
            badge.setCornerRadius(dp(6));
            badge.setColor(accent);
            ranks[i].setBackground(badge);
            titles[i].setText(it.title);
            metas[i].setText(it.meta);
            card.setOnClickListener(v -> onPick.pick(it));
            card.setAlpha(0f);
            card.setTranslationY(dp(26));
            card.animate().alpha(1f).translationY(0f).setDuration(280).setStartDelay(70 + i * 45L)
                .setInterpolator(ease).withLayer().start();
        }
    }

    void hide() {
        if (shownKey == null) return;
        shownKey = null;
        for (LinearLayout card : cards) card.setFocusable(false);
        animate().cancel();
        animate().alpha(0f).setDuration(150).start();
    }
}
