package com.oso.xmbhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;

import java.time.LocalDateTime;
import java.util.Random;

/**
 * The background: the PS3 month colour at the hour's brightness, plus PS3-style waves: a bundle of
 * thin translucent ribbons flowing across the middle of the screen, each one twisting (its width
 * swings through zero, so the two edges cross over), with sparkles that drift along the wave and
 * twinkle. Redraws at 30 fps only while started; the colour is recomputed once a minute.
 *
 * Everything is a pure function of the running time, so there is no per-frame state to update.
 *
 * Drawn at full resolution into its own GPU layer, so frames where only the menu changes (the
 * breathing glow runs at 60 fps) reuse the layer instead of repainting. Each ribbon is filled as
 * a band that never crosses itself (centre +/- |width|), which keeps the fill free of seams; the
 * two edge lines still swap sides at every twist.
 */
final class WaveView extends View implements Choreographer.FrameCallback {
    private static final long FRAME_NS = 33_333_333L;
    private static final double TAU = Math.PI * 2;
    private static final float STEP = 4;             // dp between points along each ribbon

    // Tuning: the wave's centre line and the bundle of ribbons around it (dp, radians/second).
    private static final float WAVE_Y = 300;         // vertical centre of the wave on the 540 dp screen
    private static final float WAVE_AMP = 58, WAVE_AMP2 = 20;
    private static final double WAVE_K = TAU / 1500, WAVE_K2 = TAU / 640;
    private static final double WAVE_SPEED = .20, WAVE_SPEED2 = -.11;

    private static final int RIBBONS = 5;
    private static final int SPARKS = 40;
    private static final long SCATTER_MS = 650, GATHER_MS = 900;
    private static final float REVEAL_SOFT = 260;    // dp: width of the soft leading edge of the boot sweep

    private final float dp;
    private final Paint bg = new Paint(), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
            edge = new Paint(Paint.ANTI_ALIAS_FLAG), haze = new Paint(Paint.ANTI_ALIAS_FLAG),
            spark = new Paint(Paint.ANTI_ALIAS_FLAG), glint = new Paint(Paint.ANTI_ALIAS_FLAG),
            mask = new Paint();
    private long revealStart, revealMs, scatterAt, gatherAt;   // uptime ms; 0 = not happening
    private final Path path = new Path();
    private final Ribbon[] ribbons = new Ribbon[RIBBONS];
    private final Spark[] sparks = new Spark[SPARKS];
    private float[] top = new float[0], bottom = new float[0];
    private int colour = -1, ribbonRgb = 0xFFFFFF;
    private float brightness = -1;
    private boolean running;
    private long lastFrameNs;
    private double seconds;
    private java.util.function.DoubleConsumer onTick;

    /** Called on every 30 fps tick with the running time, so other slow animations can share it. */
    void setOnTick(java.util.function.DoubleConsumer tick) { onTick = tick; }

    /** One ribbon: offset from the wave's centre line, how wide it gets, and how it twists. */
    private static final class Ribbon {
        final float offset, width, fillAlpha, edgeAlpha;
        final double lag, twistK, twistSpeed, twistPhase;

        Ribbon(float offset, float width, double lag, double twistK, double twistSpeed, double twistPhase,
               float fillAlpha, float edgeAlpha) {
            this.offset = offset; this.width = width; this.lag = lag;
            this.twistK = twistK; this.twistSpeed = twistSpeed; this.twistPhase = twistPhase;
            this.fillAlpha = fillAlpha; this.edgeAlpha = edgeAlpha;
        }
    }

    /** A sparkle: where it sits along and around the wave, how it drifts and how often it twinkles. */
    private static final class Spark {
        final float x0, dy, vx, size;
        final double bob, phase, freq;
        float fly, turn;   // scatter: how far it flies (dp) and how far off straight-out it veers

        Spark(float x0, float dy, float vx, float size, double bob, double phase, double freq) {
            this.x0 = x0; this.dy = dy; this.vx = vx; this.size = size;
            this.bob = bob; this.phase = phase; this.freq = freq;
        }
    }

    WaveView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(.9f * dp);
        edge.setStrokeJoin(Paint.Join.ROUND);
        glint.setStyle(Paint.Style.STROKE);
        glint.setStrokeCap(Paint.Cap.ROUND);
        glint.setStrokeWidth(.6f * dp);
        Random r = new Random(1983);   // fixed seed: the same wave every time
        for (int i = 0; i < RIBBONS; i++) {
            float spread = (i - (RIBBONS - 1) / 2f);
            ribbons[i] = new Ribbon(
                    spread * 7f,                             // fanned out a little around the centre line
                    16 + r.nextFloat() * 26,                 // widest point, dp
                    spread * .16,                            // each ribbon trails its neighbour slightly
                    TAU / (520 + r.nextFloat() * 700),       // twist length along the screen
                    (r.nextBoolean() ? 1 : -1) * (.12 + r.nextFloat() * .22),
                    r.nextDouble() * TAU,
                    .035f + r.nextFloat() * .045f,
                    .16f + r.nextFloat() * .22f);
        }
        for (int i = 0; i < SPARKS; i++) {
            sparks[i] = new Spark(
                    r.nextFloat() * 1100,                    // starting x along a 1100 dp loop
                    (float) (r.nextGaussian() * 46),         // above or below the wave
                    6 + r.nextFloat() * 16,                  // drifts right, dp per second
                    .5f + r.nextFloat() * 1.1f,
                    TAU / (5 + r.nextFloat() * 7),
                    r.nextDouble() * TAU,
                    .5 + r.nextFloat() * 1.6);
        }
        for (Spark s : sparks) {
            s.fly = 160 + r.nextFloat() * 340;
            s.turn = (r.nextFloat() - .5f) * 1.4f;
        }
        mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        setLayerType(LAYER_TYPE_HARDWARE, null);
        refreshSky();
    }

    /** Boot: the ribbons and sparkles sweep in from the left, starting after delayMs. */
    void reveal(long delayMs, long durationMs) {
        revealStart = SystemClock.uptimeMillis() + delayMs;
        revealMs = durationMs;
        scatterAt = 0;
        gatherAt = 0;
        invalidate();
    }

    /** An app is opening: every sparkle bursts outwards and fades. */
    void scatter() {
        scatterAt = SystemClock.uptimeMillis();
        gatherAt = 0;
    }

    /** Back on Home: scattered sparkles fade back in where they belong. */
    void gather() {
        if (scatterAt == 0) return;
        scatterAt = 0;
        gatherAt = SystemClock.uptimeMillis();
    }

    /** A key press skipped the boot: finish the sweep at once. */
    void finishReveal() {
        revealMs = 0;
        invalidate();
    }

    /** Wake without the boot sweep: everything simply there. */
    void resetEffects() {
        revealMs = 0;
        scatterAt = 0;
        gatherAt = 0;
    }

    private static float clamp01(float v) { return v < 0 ? 0 : v > 1 ? 1 : v; }

    /** Called on start and every minute (TIME_TICK). */
    void refreshSky() {
        LocalDateTime now = Sky.now();
        int c = Sky.colour(now);
        float b = Sky.brightness(now);
        if (c == colour && b == brightness && getWidth() > 0 && bg.getShader() != null) return;
        colour = c;
        brightness = b;
        buildShaders();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        int n = (int) Math.ceil(w / dp / STEP) + 2;
        top = new float[n];
        bottom = new float[n];
        buildShaders();
    }

    private void buildShaders() {
        int w = getWidth(), h = getHeight();
        if (w == 0) return;
        int lit = Sky.scale(colour, brightness);
        // CSS 170deg: top-left-ish to bottom-right-ish, darker at the top so the clock stays readable.
        bg.setShader(new LinearGradient(w * 0.54f, 0, w * 0.44f, h,
                new int[] {0xFF000000 | Sky.scale(lit, .45f), 0xFF000000 | Sky.scale(lit, .8f), 0xFF000000 | lit},
                new float[] {0, .6f, 1}, Shader.TileMode.CLAMP));
        // The ribbons are a pale version of the month colour, so they read on black and on white.
        ribbonRgb = Sky.lift(colour, .75f);
        // A soft glow band behind the ribbons, fading out above and below.
        float c = WAVE_Y * dp, spread = 120 * dp;
        haze.setShader(new LinearGradient(0, c - spread, 0, c + spread,
                new int[] {0, alpha(ribbonRgb, .07f), 0}, new float[] {0, .5f, 1}, Shader.TileMode.CLAMP));
    }

    private static int alpha(int rgb, float a) {
        return (Math.max(0, Math.min(255, Math.round(a * 255))) << 24) | (rgb & 0xFFFFFF);
    }

    /** The wave's centre line in dp at x dp, for a ribbon trailing by lag. */
    private static float centre(float x, double t, double lag) {
        return (float) (WAVE_Y
                + WAVE_AMP * Math.sin(x * WAVE_K + t * WAVE_SPEED - lag)
                + WAVE_AMP2 * Math.sin(x * WAVE_K2 + t * WAVE_SPEED2 + lag * 1.7));
    }

    void start() {
        if (running) return;
        running = true;
        lastFrameNs = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }

    void stop() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!running) return;
        if (lastFrameNs == 0 || frameTimeNanos - lastFrameNs >= FRAME_NS) {
            if (lastFrameNs != 0) seconds += (frameTimeNanos - lastFrameNs) / 1e9;
            lastFrameNs = frameTimeNanos;
            invalidate();
            if (onTick != null) onTick.accept(seconds);
        }
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        canvas.drawRect(0, 0, w, h, bg);
        long now = SystemClock.uptimeMillis();
        float reveal = 1;
        if (revealMs > 0) {
            reveal = clamp01((now - revealStart) / (float) revealMs);
            if (reveal >= 1) revealMs = 0;
            else if (reveal <= 0) return;   // still black: only the background, fading in
        }
        if (reveal < 1) {
            // Sweep in from the left: draw the wave into a layer, then keep only what's left of a
            // soft edge moving across the screen (eased, so it glides in and settles).
            float e = reveal * reveal * (3 - 2 * reveal);
            float soft = REVEAL_SOFT * dp, edgeX = e * (w + soft);
            int save = canvas.saveLayer(0, 0, w, h, null);
            drawWave(canvas, w, h, now);
            mask.setShader(new LinearGradient(edgeX - soft, 0, edgeX, 0, 0xFF000000, 0x00000000, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, mask);
            canvas.restoreToCount(save);
        } else {
            drawWave(canvas, w, h, now);
        }
    }

    private void drawWave(Canvas canvas, int w, int h, long now) {
        float wDp = w / dp;
        double t = seconds;
        float scatter = scatterAt > 0 ? clamp01((now - scatterAt) / (float) SCATTER_MS) : 0;
        float gather = 1;
        if (gatherAt > 0) {
            gather = clamp01((now - gatherAt) / (float) GATHER_MS);
            if (gather >= 1) gatherAt = 0;
        }

        // Haze: follows the wave loosely by shifting the band with the centre line's mid point.
        canvas.save();
        canvas.translate(0, (centre(wDp / 2, t, 0) - WAVE_Y) * dp * .5f);
        canvas.drawRect(0, 0, w, h, haze);
        canvas.restore();

        int n = top.length;
        for (Ribbon rb : ribbons) {
            if (n < 2) break;
            // Width swings through zero, so the edges meet and swap: that is the ribbon's twist. The
            // band uses |width|, so the filled shape never crosses itself (no seams in the fill).
            for (int i = 0; i < n; i++) {
                float x = i * STEP;
                float c = centre(x, t, rb.lag) + rb.offset;
                float half = (float) Math.abs(rb.width * .5 * Math.sin(x * rb.twistK + t * rb.twistSpeed + rb.twistPhase));
                top[i] = (c - half) * dp;
                bottom[i] = (c + half) * dp;
            }
            path.rewind();
            path.moveTo(0, top[0]);
            for (int i = 1; i < n; i++) path.lineTo(i * STEP * dp, top[i]);
            for (int i = n - 1; i >= 0; i--) path.lineTo(i * STEP * dp, bottom[i]);
            path.close();
            fill.setColor(alpha(ribbonRgb, rb.fillAlpha));
            canvas.drawPath(path, fill);

            edge.setColor(alpha(0xFFFFFF, rb.edgeAlpha));
            path.rewind();
            path.moveTo(0, top[0]);
            for (int i = 1; i < n; i++) path.lineTo(i * STEP * dp, top[i]);
            canvas.drawPath(path, edge);
            path.rewind();
            path.moveTo(0, bottom[0]);
            for (int i = 1; i < n; i++) path.lineTo(i * STEP * dp, bottom[i]);
            canvas.drawPath(path, edge);
        }

        // Sparkles: ride along the wave, drift right, wrap around, and twinkle now and then.
        float loop = Math.max(1100, wDp + 60);
        for (Spark s : sparks) {
            float x = (float) ((s.x0 + s.vx * t) % loop) - 30;
            if (x < -10 || x > wDp + 10) continue;
            float y = centre(x, t, 0) + s.dy + (float) (6 * Math.sin(t * s.bob + s.phase));
            double tw = Math.max(0, Math.sin(t * s.freq + s.phase));
            float a = (float) (.12 + .88 * tw * tw * tw);
            if (scatter > 0) {
                if (scatter >= 1) continue;
                // Burst outwards from the middle of the screen, flaring bright, then fading out.
                double ang = Math.atan2(y - 270, x - wDp / 2) + s.turn;
                float out = 1 - (1 - scatter) * (1 - scatter) * (1 - scatter);
                x += (float) Math.cos(ang) * s.fly * out;
                y += (float) Math.sin(ang) * s.fly * out;
                a = Math.max(a, .95f) * (1 - scatter);
            }
            a *= gather;
            float px = x * dp, py = y * dp;
            spark.setColor(alpha(0xFFFFFF, a * .16f));
            canvas.drawCircle(px, py, s.size * 3.2f * dp, spark);
            spark.setColor(alpha(0xFFFFFF, a * .9f));
            canvas.drawCircle(px, py, s.size * dp, spark);
            if (a > .55f) {   // at its brightest a sparkle flares into a small four-point glint
                float g = s.size * 5.5f * dp * (a - .45f) / .55f;
                glint.setColor(alpha(0xFFFFFF, (a - .55f) * 1.4f));
                canvas.drawLine(px - g, py, px + g, py, glint);
                canvas.drawLine(px, py - g, px, py + g, glint);
            }
        }
    }
}
