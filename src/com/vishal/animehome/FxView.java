package com.vishal.animehome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;

import java.util.Random;

/**
 * Full-screen overlay that draws a short theme effect around the focused tile.
 * Effects run once on focus and then go idle, so nothing animates while you just sit on the home screen.
 */
final class FxView extends View {
    static final int NONE = -1, SLASH = 0, LIGHTNING = 1, SCROLL = 2, SPIRAL = 3, JUMP = 4;
    private static final long[] DURATION = {520, 650, 480, 1000, 900};
    private static final int FEATHERS = 9;
    /** Slow-motion factor for checking effects with screenshots; always 1 in normal use. */
    static float slow = 1f;

    private final float d;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path cut = new Path();
    private final RectF r = new RectF();
    private final RectF tmp = new RectF();
    private final int[] loc = new int[2];
    private final int[] self = new int[2];
    private final Random rnd = new Random();

    private View target;
    private int kind, accent;
    private float t;
    private boolean active;
    private long seed;
    private ValueAnimator anim;

    private final float[] fx = new float[FEATHERS], fvx = new float[FEATHERS], fvy = new float[FEATHERS];
    private final float[] frot = new float[FEATHERS], fspin = new float[FEATHERS];
    private float ox, oy;
    private boolean originSet;

    FxView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        setFocusable(false);
        setClickable(false);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
    }

    void play(int kind, View target, int accent) {
        cancelAnim();
        this.kind = kind;
        this.target = target;
        this.accent = accent;
        this.seed = System.nanoTime();
        this.originSet = false;
        this.active = true;
        this.t = 0f;
        if (kind == JUMP) {
            initFeathers();
            ObjectAnimator jump = ObjectAnimator.ofFloat(target, "translationY", 0f, -16 * d, 0f);
            jump.setDuration((long) (420 * slow));
            jump.start();
        }
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration((long) (DURATION[kind] * slow));
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> {
            t = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            boolean cancelled;

            @Override public void onAnimationCancel(Animator a) { cancelled = true; }

            @Override public void onAnimationEnd(Animator a) {
                if (cancelled) return;
                // The scroll frame stays around the focused tile; everything else disappears.
                active = FxView.this.kind == SCROLL;
                invalidate();
            }
        });
        anim.start();
    }

    void clear(View v) {
        if (target != v) return;
        cancelAnim();
        if (v != null) v.setTranslationY(0f);
        target = null;
        active = false;
        invalidate();
    }

    private void cancelAnim() {
        if (anim != null) anim.cancel();
        anim = null;
    }

    @Override protected void onDraw(Canvas c) {
        if (!active || target == null || !target.isAttachedToWindow()) return;
        target.getLocationInWindow(loc);
        getLocationInWindow(self);
        float x = loc[0] - self[0], y = loc[1] - self[1];
        r.set(x, y, x + target.getWidth() * target.getScaleX(), y + target.getHeight() * target.getScaleY());
        switch (kind) {
            case SLASH: drawSlash(c); break;
            case LIGHTNING: drawLightning(c); break;
            case SCROLL: drawScroll(c); break;
            case SPIRAL: drawSpiral(c); break;
            case JUMP: drawFeathers(c); break;
        }
    }

    /** Ichigo: a Getsuga Tensho crescent sweeps across the tile. */
    private void drawSlash(Canvas c) {
        float a = (float) Math.sin(Math.PI * t);
        float R = r.height() * 1.1f;
        float cx = r.left - R * 0.6f + t * (r.width() + R * 1.2f), cy = r.centerY();
        path.reset();
        path.addCircle(cx, cy, R, Path.Direction.CW);
        cut.reset();
        cut.addCircle(cx - R * 0.28f, cy, R * 0.96f, Path.Direction.CW);
        path.op(cut, Path.Op.DIFFERENCE);
        c.save();
        c.clipRect(r.left - 20 * d, r.top - 20 * d, r.right + 20 * d, r.bottom + 20 * d);
        c.rotate(-18f, r.centerX(), r.centerY());
        fill.setShader(null);
        fill.setColor(0xFF0D0D0D);
        fill.setAlpha((int) (a * 220));
        c.drawPath(path, fill);
        line.setColor(accent);
        line.setStrokeWidth(5 * d);
        line.setAlpha((int) (a * 120));
        c.drawPath(path, line);
        line.setColor(0xFFFFFFFF);
        line.setStrokeWidth(1.5f * d);
        line.setAlpha((int) (a * 255));
        c.drawPath(path, line);
        c.restore();
    }

    /** Kakashi: Chidori lightning crackles over the tile. */
    private void drawLightning(Canvas c) {
        float a = 1f - t;
        rnd.setSeed(seed + (int) (t * 14));
        for (int b = 0; b < 3; b++) {
            float[] p0 = perimeterPoint(), p1 = perimeterPoint();
            path.reset();
            path.moveTo(p0[0], p0[1]);
            int segs = 7;
            float dx = p1[0] - p0[0], dy = p1[1] - p0[1];
            float len = (float) Math.hypot(dx, dy) + 1f;
            float nx = -dy / len, ny = dx / len;
            for (int i = 1; i < segs; i++) {
                float f = i / (float) segs, off = (rnd.nextFloat() * 2f - 1f) * 12 * d;
                path.lineTo(p0[0] + dx * f + nx * off, p0[1] + dy * f + ny * off);
            }
            path.lineTo(p1[0], p1[1]);
            line.setColor(accent);
            line.setStrokeWidth(6 * d);
            line.setAlpha((int) (a * 110));
            c.drawPath(path, line);
            line.setColor(0xFFFFFFFF);
            line.setStrokeWidth(1.8f * d);
            line.setAlpha((int) (a * 255));
            c.drawPath(path, line);
        }
    }

    private float[] perimeterPoint() {
        float u = rnd.nextFloat();
        switch (rnd.nextInt(4)) {
            case 0: return new float[] {r.left + u * r.width(), r.top};
            case 1: return new float[] {r.right, r.top + u * r.height()};
            case 2: return new float[] {r.left + u * r.width(), r.bottom};
            default: return new float[] {r.left, r.top + u * r.height()};
        }
    }

    /** Jiraiya: a parchment scroll unrolls around the tile and stays while it is focused. */
    private void drawScroll(Canvas c) {
        float e = 1f - (1f - t) * (1f - t);
        float half = (r.width() / 2f + 14 * d) * e;
        tmp.set(r.centerX() - half, r.top - 10 * d, r.centerX() + half, r.bottom + 10 * d);
        path.reset();
        path.setFillType(Path.FillType.EVEN_ODD);
        path.addRoundRect(tmp, 6 * d, 6 * d, Path.Direction.CW);
        path.addRoundRect(r, 8 * d, 8 * d, Path.Direction.CW);
        fill.setShader(null);
        fill.setColor(0xF0E9DCB5);
        c.save();
        c.clipRect(tmp);
        c.drawPath(path, fill);
        c.restore();
        line.setColor(0xFFB71C1C);
        line.setStrokeWidth(1.5f * d);
        line.setAlpha((int) (e * 255));
        tmp.inset(4 * d, 4 * d);
        c.drawRoundRect(tmp, 4 * d, 4 * d, line);
        tmp.inset(-4 * d, -4 * d);
        for (float rx : new float[] {tmp.left, tmp.right}) {
            fill.setColor(0xFF5D4037);
            c.drawRoundRect(rx - 4.5f * d, tmp.top - 6 * d, rx + 4.5f * d, tmp.bottom + 6 * d, 4 * d, 4 * d, fill);
            fill.setColor(0xFFFFC107);
            c.drawCircle(rx, tmp.top - 6 * d, 4 * d, fill);
            c.drawCircle(rx, tmp.bottom + 6 * d, 4 * d, fill);
        }
    }

    /** Naruto: a Rasengan swirl spins around the tile. */
    private void drawSpiral(Canvas c) {
        float a = t < 0.75f ? 1f : (1f - t) / 0.25f;
        float cx = r.centerX(), cy = r.centerY();
        float base = r.width() * 0.5f + 6 * d;
        fill.setShader(new RadialGradient(cx, cy, base * 1.15f, 0x66B3E5FC, 0x00B3E5FC, Shader.TileMode.CLAMP));
        fill.setAlpha((int) (a * 255));
        c.drawCircle(cx, cy, base * 1.15f, fill);
        fill.setShader(null);
        float rot = t * 900f;
        for (int i = 0; i < 4; i++) {
            float rad = base * (0.6f + 0.15f * i);
            tmp.set(cx - rad, cy - rad * 0.62f, cx + rad, cy + rad * 0.62f);
            line.setColor(i % 2 == 0 ? 0xFF4FC3F7 : 0xFFFFFFFF);
            line.setStrokeWidth((i % 2 == 0 ? 3.5f : 1.8f) * d);
            line.setAlpha((int) (a * 230));
            c.drawArc(tmp, rot * (1f + 0.3f * i) + i * 90f, 80f, false, line);
        }
    }

    /** Hinata: crow feathers burst out as the tile jumps. */
    private void initFeathers() {
        Random g = new Random(seed);
        for (int i = 0; i < FEATHERS; i++) {
            fx[i] = (g.nextFloat() - 0.5f);
            fvx[i] = (g.nextFloat() * 2f - 1f) * 160 * d;
            fvy[i] = -(220 + g.nextFloat() * 180) * d;
            frot[i] = g.nextFloat() * 360f;
            fspin[i] = (g.nextFloat() * 2f - 1f) * 540f;
        }
    }

    private void drawFeathers(Canvas c) {
        if (!originSet) {
            ox = r.centerX();
            oy = r.bottom;
            originSet = true;
        }
        float s = t * 0.9f, a = 1f - t, g = 520 * d;
        for (int i = 0; i < FEATHERS; i++) {
            float x = ox + fx[i] * r.width() + fvx[i] * s;
            float y = oy + fvy[i] * s + 0.5f * g * s * s;
            c.save();
            c.translate(x, y);
            c.rotate(frot[i] + fspin[i] * s);
            tmp.set(-3.5f * d, -11 * d, 3.5f * d, 11 * d);
            fill.setShader(null);
            fill.setColor(0xFF141414);
            fill.setAlpha((int) (a * 255));
            c.drawOval(tmp, fill);
            line.setColor(0xFFFFFFFF);
            line.setStrokeWidth(1f * d);
            line.setAlpha((int) (a * 170));
            c.drawOval(tmp, line);
            line.setColor(accent);
            line.setAlpha((int) (a * 255));
            c.drawLine(0, -9 * d, 0, 11 * d, line);
            c.restore();
        }
    }
}
