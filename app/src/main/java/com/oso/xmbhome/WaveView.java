package com.oso.xmbhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.RadialGradient;
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

    private static final int RIBBONS = 12;
    // The bundle keeps the same overall thickness however many ribbons there are, and each ribbon
    // gets fainter as the count rises, so more ribbons means richer layering, not a brighter blob.
    private static final float BUNDLE_HALF = 30;   // dp from the middle ribbon to the outermost
    private static final float RIBBON_FADE = (float) Math.sqrt(5.0 / RIBBONS);
    // Particles: a fine dust, dense around the wave and thinning out above and below it. Most are
    // tiny crisp dots; one in GLOW_EVERY is a little larger with a soft glow.
    private static final int SPARKS = 272, GLOW_EVERY = 10;
    private static final long SCATTER_MS = 420, GATHER_MS = 900;   // scatter is a quick rush outwards
    // How lively the particles are once settled: 1.0 = original pace. 1.83 = 1.7 x 1.075.
    private static final float PARTICLE_PACE = 1.83f;
    private static final float NEAR_PACE = 1.075f;   // the edge glows' wander speed
    private static final long ARRIVE_MS = 2600;   // boot: particles drift out from the ribbons and settle
    private static final int LEVELS = 6;                           // brightness steps for batched drawing

    private final float dp;
    private final Paint bg = new Paint(), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
            edge = new Paint(Paint.ANTI_ALIAS_FLAG), haze = new Paint(Paint.ANTI_ALIAS_FLAG),
            spark = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix glowMatrix = new Matrix();
    // Near particles: a few big, soft, out-of-focus glows near the screen edges, as if floating
    // closer to the viewer. They wander like fireflies and live longer than the dust. Which edges
    // get them is picked at random on each start: both (60%), left only (20%) or right only (20%).
    private static final int NEAR = 26;
    private static final float NEAR_ZONE = 230;   // dp in from the edge they keep to
    private final float[] nearSize = new float[NEAR], nearDy = new float[NEAR], nearLife = new float[NEAR],
            nearPhase = new float[NEAR], nearF1 = new float[NEAR], nearF2 = new float[NEAR];
    private final Paint bokeh = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int nearSides = 3;   // 1 = left, 2 = right, 3 = both
    private long scatterAt, gatherAt;   // uptime ms; 0 = not happening
    // Boot: from bootStart (uptime ms; 0 = no boot), the screen stays black, the ribbons fade in at
    // ribbonsAt, then the colour fades in at colourAt (both ms after bootStart).
    private long bootStart, ribbonsAt, ribbonsMs, colourAt, colourMs;
    private long arriveStart;   // uptime ms the particles start flying in; 0 = already in place
    private final Path path = new Path();
    private final Ribbon[] ribbons = new Ribbon[RIBBONS];
    private final Spark[] sparks = new Spark[SPARKS];
    // Batched drawing: dots are sorted into LEVELS brightness steps x 2 sizes and drawn with one
    // drawPoints call per batch, so hundreds of dots cost about a dozen draw calls.
    private final float[][] batch = new float[LEVELS * 2][SPARKS * 2];
    private final int[] batchN = new int[LEVELS * 2];
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float[] top = new float[0], bottom = new float[0];
    // Liquid ribbons (config.xml: liquid_ribbons). Each ribbon is drawn as a strip of triangles with
    // a colour per point, so it can be shaded along its length: brighter and denser where it folds
    // edge-on, lit on one side of each twist, with edges that glow at the folds. Off = the original
    // flat ribbons, drawn by the untouched code below.
    private final boolean liquid;
    // Each ribbon is a grid: FILL_ROWS points across it (feather, edge, upper sheen, centre, lower
    // sheen, edge, feather) and
    // EDGE_ROWS across each edge line (fade, line, fade). The outer rows are fully transparent, so
    // every edge fades out over a pixel or two: that is the anti-aliasing.
    private static final int FILL_ROWS = 7, EDGE_ROWS = 3;
    private float[] fillV = new float[0], topV = new float[0], botV = new float[0];
    private int[] fillC = new int[0], topC = new int[0], botC = new int[0], bloomC = new int[0];
    private float[] bloomV = new float[0];
    private short[] fillIdx = new short[0], edgeIdx = new short[0];
    private final Paint strip = new Paint(Paint.ANTI_ALIAS_FLAG);
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
        float fly, angle;  // scatter: how far it flies (dp) and in which direction
        boolean glows;     // one of the larger, glowing ones
        int id;            // seeds each life's random spot, direction and speed
        float life;        // seconds per life: appear, drift, fade out, then respawn nearby

        Spark(float x0, float dy, float vx, float size, double bob, double phase, double freq) {
            this.x0 = x0; this.dy = dy; this.vx = vx; this.size = size;
            this.bob = bob; this.phase = phase; this.freq = freq;
        }
    }

    WaveView(Context c) {
        super(c);
        liquid = c.getResources().getBoolean(R.bool.liquid_ribbons);
        strip.setColor(0xFFFFFFFF);
        dp = c.getResources().getDisplayMetrics().density;
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(.9f * dp);
        edge.setStrokeJoin(Paint.Join.ROUND);
        // One soft round glow, radius 1 at the origin, scaled and moved onto each sparkle.
        glow.setShader(new RadialGradient(0, 0, 1,
                new int[] {0xFFFFFFFF, 0x66FFFFFF, 0x1AFFFFFF, 0x00FFFFFF},
                new float[] {0, .18f, .45f, 1}, Shader.TileMode.CLAMP));
        Random r = new Random(1983);   // fixed seed: the same wave every time
        for (int i = 0; i < RIBBONS; i++) {
            float spread = (i - (RIBBONS - 1) / 2f);
            float pos = RIBBONS > 1 ? spread / ((RIBBONS - 1) / 2f) : 0;   // -1 .. 1 across the bundle
            ribbons[i] = new Ribbon(
                    pos * BUNDLE_HALF,                       // fanned out a little around the centre line
                    16 + r.nextFloat() * 26,                 // widest point, dp
                    pos * .4,                                // each ribbon trails its neighbour slightly
                    TAU / (520 + r.nextFloat() * 700),       // twist length along the screen
                    (r.nextBoolean() ? 1 : -1) * (.12 + r.nextFloat() * .22),
                    r.nextDouble() * TAU,
                    (.035f + r.nextFloat() * .045f) * RIBBON_FADE,
                    (.16f + r.nextFloat() * .22f) * RIBBON_FADE);
        }
        for (int i = 0; i < SPARKS; i++) {
            boolean glows = i % GLOW_EVERY == 0;
            // Most hug the wave closely; a third spread wider, so the dust thins out with distance.
            float spread = r.nextFloat() < .66f ? 22 : 70;
            sparks[i] = new Spark(
                    r.nextFloat() * 1100,                    // starting x along a 1100 dp loop
                    (float) (r.nextGaussian() * spread),     // above or below the wave
                    4 + r.nextFloat() * 12,                  // drifts right, dp per second
                    glows ? .9f + r.nextFloat() * .7f : .45f + r.nextFloat() * .55f,
                    TAU / (6 + r.nextFloat() * 8),
                    r.nextDouble() * TAU,
                    .4 + r.nextFloat() * 1.4);
            sparks[i].glows = glows;
            sparks[i].id = i;
            sparks[i].life = (3 + r.nextFloat() * 4) / PARTICLE_PACE;   // shorter lives at a faster pace
            sparks[i].fly = 350 + r.nextFloat() * 650;          // rushes far off-screen
            sparks[i].angle = (float) (r.nextDouble() * TAU);    // in its own random direction
        }
        for (int i = 0; i < NEAR; i++) {
            nearSize[i] = 3 + r.nextFloat() * 4.5f;             // dp radius: bigger than the dust
            nearDy[i] = (float) (r.nextGaussian() * 55);        // around the wave, a little looser
            nearLife[i] = 5 + r.nextFloat() * 5;               // seconds: slow, unhurried lives
            nearPhase[i] = r.nextFloat();
            nearF1[i] = .25f + r.nextFloat() * .35f;            // firefly wander speeds
            nearF2[i] = .3f + r.nextFloat() * .4f;
        }
        // Out of focus: a soft disc that fades gently to its edge, no hard bright core.
        bokeh.setShader(new RadialGradient(0, 0, 1,
                new int[] {0x99FFFFFF, 0x80FFFFFF, 0x40FFFFFF, 0x00FFFFFF},
                new float[] {0, .4f, .75f, 1}, Shader.TileMode.CLAMP));
        pickSides();
        dot.setStyle(Paint.Style.STROKE);
        dot.setStrokeCap(Paint.Cap.ROUND);
        dot.setColor(0xFFFFFFFF);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        refreshSky();
    }

    /**
     * Boot: starts on black. The ribbons and sparkles fade in at ribbonsAt over ribbonsMs while the
     * background stays black; then the colour fades in at colourAt over colourMs.
     */
    /** Which edges get the near particles this time: both most often, sometimes just one. */
    private void pickSides() {
        double p = Math.random();
        nearSides = p < .6 ? 3 : p < .8 ? 1 : 2;
    }

    void boot(long ribbonsAt, long ribbonsMs, long colourAt, long colourMs, long particlesAt) {
        pickSides();
        bootStart = SystemClock.uptimeMillis();
        arriveStart = bootStart + particlesAt;   // hidden until then, then they fly in
        this.ribbonsAt = ribbonsAt;
        this.ribbonsMs = Math.max(1, ribbonsMs);
        this.colourAt = colourAt;
        this.colourMs = Math.max(1, colourMs);
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

    /** A key press skipped the boot: colour and ribbons straight in. */
    void finishReveal() {
        bootStart = 0;
        arriveStart = 0;
        invalidate();
    }

    /** Wake without the boot: everything simply there. */
    void resetEffects() {
        bootStart = 0;
        arriveStart = 0;
        scatterAt = 0;
        gatherAt = 0;
    }

    private static float clamp01(float v) { return v < 0 ? 0 : v > 1 ? 1 : v; }

    /** A repeatable random number in [0, 1) for particle id, life number and slot. */
    private static float hash(int id, long life, int slot) {
        long h = id * 0x9E3779B97F4A7C15L ^ (life + 0x632BE59BD9B4E019L) * 0xBF58476D1CE4E5B9L
                ^ (slot + 1) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        h *= 0xD6E8FEB86659FD93L;
        h ^= h >>> 32;
        return (h >>> 40) / (float) (1L << 24);
    }

    private static float ease(float v) { return v * v * (3 - 2 * v); }

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
        fillV = new float[n * FILL_ROWS * 2];
        topV = new float[n * EDGE_ROWS * 2];
        botV = new float[n * EDGE_ROWS * 2];
        fillC = new int[n * FILL_ROWS];
        topC = new int[n * EDGE_ROWS];
        botC = new int[n * EDGE_ROWS];
        bloomV = new float[n * EDGE_ROWS * 2];
        bloomC = new int[n * EDGE_ROWS];
        fillIdx = gridIndices(n, FILL_ROWS);
        edgeIdx = gridIndices(n, EDGE_ROWS);
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
        // A soft glow band behind the ribbons, fading out above and below. It follows the hour's
        // brightness, so on a dark night background it doesn't show up as a grey band of fog.
        float c = WAVE_Y * dp, spread = 120 * dp;
        haze.setShader(new LinearGradient(0, c - spread, 0, c + spread,
                new int[] {0, alpha(ribbonRgb, .07f * brightness), 0}, new float[] {0, .5f, 1}, Shader.TileMode.CLAMP));
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
        long now = SystemClock.uptimeMillis();
        float ribbons = 1, sky = 1;
        if (bootStart > 0) {
            long e = now - bootStart;
            // Ease-out: the ribbons show up straight away and settle gently, not a slow start.
            float rv = clamp01((e - ribbonsAt) / (float) ribbonsMs);
            ribbons = 1 - (1 - rv) * (1 - rv);
            sky = ease(clamp01((e - colourAt) / (float) colourMs));
            if (ribbons >= 1 && sky >= 1) bootStart = 0;
        }
        // Background: black, with the colour faded in over it (plain alpha, no masks or sweeps).
        if (sky < 1) canvas.drawColor(0xFF000000);
        if (sky > 0) {
            bg.setAlpha(Math.round(sky * 255));
            canvas.drawRect(0, 0, w, h, bg);
            bg.setAlpha(255);
        }
        if (ribbons <= 0) return;
        if (ribbons < 1) {
            // The whole wave (ribbons and sparkles) fades as one, evenly across the screen.
            int save = canvas.saveLayerAlpha(0, 0, w, h, Math.round(ribbons * 255));
            drawWave(canvas, w, h, now);
            canvas.restoreToCount(save);
        } else {
            drawWave(canvas, w, h, now);
        }
    }

    /** Triangle indices for a grid of n columns x rows points (column-major), built once. */
    private static short[] gridIndices(int n, int rows) {
        short[] a = new short[Math.max(0, n - 1) * (rows - 1) * 6];
        int p = 0;
        for (int i = 0; i < n - 1; i++) {
            for (int r = 0; r < rows - 1; r++) {
                int v0 = i * rows + r, v1 = v0 + 1, v2 = v0 + rows, v3 = v2 + 1;
                a[p++] = (short) v0; a[p++] = (short) v2; a[p++] = (short) v1;
                a[p++] = (short) v1; a[p++] = (short) v2; a[p++] = (short) v3;
            }
        }
        return a;
    }

    private static int e0(int i) { return i * EDGE_ROWS * 2; }

    /** rgb moved towards white by t (0..1). */
    private static int towardWhite(int rgb, float t) {
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        r += Math.round((255 - r) * t); g += Math.round((255 - g) * t); b += Math.round((255 - b) * t);
        return (r << 16) | (g << 8) | b;
    }

    /**
     * Liquid ribbons: the same shapes and motion as the flat ones, shaded to look like flowing,
     * translucent sheets.
     *  - Density: seen edge-on (where it twists) a sheet looks denser and brighter; facing you, thinner.
     *  - Light: one side of each twist catches the light more than the other.
     *  - Flow: soft highlights travel along each ribbon, brightest down its centre, like light
     *    moving through liquid.
     *  - Depth: ribbons further from the middle of the bundle are a little dimmer, as if behind.
     *  - Smooth edges: every edge fades out over ~1.5 px (built into the shape, so no slow
     *    anti-aliasing pass), and the edge lines are soft glowing strips.
     * Three drawVertices calls per ribbon, the same as before.
     */
    private void drawLiquidRibbons(Canvas canvas, int n, double t) {
        float feather = 1.5f;                       // px: fill edges fade out over this
        float line = Math.max(1.3f, .8f * dp);      // px: half-width of the soft edge lines
        float bloomPad = 14 * dp;                   // px: how far the soft glow spreads past the ribbon
        for (Ribbon rb : ribbons) {
            float depth = 1 - .3f * Math.min(1, Math.abs(rb.offset) / BUNDLE_HALF);   // 0.7 (back) .. 1 (front)
            // Front ribbons are a touch whiter and glossier; back ones keep more of the sky colour.
            int tone = towardWhite(ribbonRgb, .2f * (depth - .7f) / .3f);
            for (int i = 0; i < n; i++) {
                float x = i * STEP;
                // Same shape and motion as the flat ribbons: all the liquid look comes from light,
                // nothing moves the geometry (no ripples or wobble).
                float c = centre(x, t, rb.lag) + rb.offset;
                float sn = (float) Math.sin(x * rb.twistK + t * rb.twistSpeed + rb.twistPhase);
                float face = Math.abs(sn);                                   // 1 = facing you, 0 = edge-on
                float half = rb.width * .5f * face;
                float px = x * dp, cy = c * dp, ty = (c - half) * dp, by = (c + half) * dp;
                float q = (by - ty) * .25f;                                  // quarter-width, for the sheen rows
                float dense = Math.min(2.2f, .55f / (face + .25f));
                float lit = sn >= 0 ? .5f : -.5f;   // stronger light/shadow sides: more volume
                // Highlights travelling along the ribbon: a broad slow one and a faster, finer one
                // going the other way, so the light seems to move through the liquid.
                float f1 = (float) (.5 + .5 * Math.sin(x * (TAU / 380) - t * .9 + rb.lag * 3));
                float f2 = (float) (.5 + .5 * Math.sin(x * (TAU / 220) + t * .8 + rb.lag * 7));
                float sheen = Math.max(f1 * f1 * f1, .75f * f2 * f2 * f2 * f2);
                float base = rb.fillAlpha * 1.5f * dense * depth;
                // Specular: a bright line on the lit side of the ribbon, strongest where it faces you.
                float spec = sheen * face;
                float specTop = lit > 0 ? spec : .3f * spec, specBot = lit > 0 ? .3f * spec : spec;

                int k = i * FILL_ROWS * 2, j = i * FILL_ROWS;
                fillV[k] = px; fillV[k + 1] = ty - feather;
                fillV[k + 2] = px; fillV[k + 3] = ty;
                fillV[k + 4] = px; fillV[k + 5] = ty + q;
                fillV[k + 6] = px; fillV[k + 7] = cy;
                fillV[k + 8] = px; fillV[k + 9] = by - q;
                fillV[k + 10] = px; fillV[k + 11] = by;
                fillV[k + 12] = px; fillV[k + 13] = by + feather;
                fillC[j] = alpha(tone, 0);
                fillC[j + 1] = alpha(tone, base * (1 + lit));
                fillC[j + 2] = alpha(towardWhite(tone, .85f * specTop), base * (1 + .6f * lit + 1.6f * specTop));
                fillC[j + 3] = alpha(towardWhite(tone, .4f * sheen), base * (1 + .8f * sheen * face));
                fillC[j + 4] = alpha(towardWhite(tone, .85f * specBot), base * (1 - .6f * lit + 1.6f * specBot));
                fillC[j + 5] = alpha(tone, base * (1 - lit));
                fillC[j + 6] = alpha(tone, 0);

                // Bloom: a soft glow around the ribbon that follows its shape and fades to nothing,
                // brighter where the highlights pass. Gives the ribbons a luminous, glossy depth.
                float spread = half * dp + bloomPad;
                bloomV[e0(i)] = px; bloomV[e0(i) + 1] = cy - spread;
                bloomV[e0(i) + 2] = px; bloomV[e0(i) + 3] = cy;
                bloomV[e0(i) + 4] = px; bloomV[e0(i) + 5] = cy + spread;
                int bc = alpha(towardWhite(tone, .3f), rb.fillAlpha * 1.1f * (.45f + sheen) * depth * depth);
                bloomC[i * EDGE_ROWS] = 0; bloomC[i * EDGE_ROWS + 1] = bc; bloomC[i * EDGE_ROWS + 2] = 0;

                float glow = rb.edgeAlpha * (.35f + 1.1f * (1 - face)) * depth * (1 + .6f * sheen);
                int e = i * EDGE_ROWS * 2, f = i * EDGE_ROWS;
                topV[e] = px; topV[e + 1] = ty - line; topV[e + 2] = px; topV[e + 3] = ty; topV[e + 4] = px; topV[e + 5] = ty + line;
                botV[e] = px; botV[e + 1] = by - line; botV[e + 2] = px; botV[e + 3] = by; botV[e + 4] = px; botV[e + 5] = by + line;
                topC[f] = 0; topC[f + 1] = alpha(0xFFFFFF, glow * (1 + lit)); topC[f + 2] = 0;
                botC[f] = 0; botC[f + 1] = alpha(0xFFFFFF, glow * (1 - lit)); botC[f + 2] = 0;
            }
            canvas.drawVertices(Canvas.VertexMode.TRIANGLES, n * EDGE_ROWS * 2, bloomV, 0, null, 0,
                    bloomC, 0, edgeIdx, 0, edgeIdx.length, strip);
            canvas.drawVertices(Canvas.VertexMode.TRIANGLES, n * FILL_ROWS * 2, fillV, 0, null, 0,
                    fillC, 0, fillIdx, 0, fillIdx.length, strip);
            canvas.drawVertices(Canvas.VertexMode.TRIANGLES, n * EDGE_ROWS * 2, topV, 0, null, 0,
                    topC, 0, edgeIdx, 0, edgeIdx.length, strip);
            canvas.drawVertices(Canvas.VertexMode.TRIANGLES, n * EDGE_ROWS * 2, botV, 0, null, 0,
                    botC, 0, edgeIdx, 0, edgeIdx.length, strip);
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

        // (No glow band behind the ribbons: it showed up as a hazy rectangle on dark backgrounds.)

        int n = top.length;
        if (liquid && n >= 2) {
            drawLiquidRibbons(canvas, n, t);
        } else for (Ribbon rb : ribbons) {
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

        // Particles: each one lives a few seconds. It appears near its spot on the wave, drifts off
        // in a random direction while it fades out, then a fresh one appears nearby and repeats.
        // The spots themselves drift slowly right along the wave and wrap around.
        float loop = Math.max(1100, wDp + 60);
        float arrive = 1;   // boot: 0 = hidden, then flying in, 1 = in place
        if (arriveStart > 0) {
            arrive = clamp01((now - arriveStart) / (float) ARRIVE_MS);
            if (arrive <= 0) return;   // not yet: ribbons only
            if (arrive >= 1) arriveStart = 0;
        }
        java.util.Arrays.fill(batchN, 0);
        for (Spark s : sparks) {
            double cycle = t / s.life + s.phase / TAU;
            long lifeNo = (long) Math.floor(cycle);   // which life this particle is on
            float u = (float) (cycle - lifeNo);        // how far through it, 0..1
            float home = (float) ((s.x0 + s.vx * t) % loop) - 30;
            float ang = hash(s.id, lifeNo, 0) * (float) TAU;
            float dist = (5 + hash(s.id, lifeNo, 1) * 9) * PARTICLE_PACE * s.life * u;   // 5-14 dp a second x pace
            float x = home + (hash(s.id, lifeNo, 2) - .5f) * 50 + (float) Math.cos(ang) * dist;
            float y = centre(x, t, 0) + s.dy + (hash(s.id, lifeNo, 3) - .5f) * 24 + (float) Math.sin(ang) * dist;
            // Quick fade in, then a long fade out as it drifts away.
            float a = (.55f + .45f * hash(s.id, lifeNo, 4)) * Math.min(1, u / .15f) * (1 - u);
            if (scatter > 0) {
                if (scatter >= 1) continue;
                // An app is opening: every particle rushes off in its own direction, fast at first
                // and easing out, fading as it goes.
                float out = 1 - (1 - scatter) * (1 - scatter) * (1 - scatter);
                x += (float) Math.cos(s.angle) * s.fly * out;
                y += (float) Math.sin(s.angle) * s.fly * out;
                a = Math.max(a, .9f) * (1 - scatter) * (1 - scatter);
            } else if (arrive < 1) {
                // Boot: each particle starts on the ribbons and drifts out to its place, a little
                // sideways in its own direction, slowing as it settles and fading in as it comes.
                float left = (1 - arrive) * (1 - arrive);
                float line = centre(x, t, 0);
                x -= (float) Math.cos(s.angle) * 60 * left;
                y += (line - y) * left;
                a *= arrive;
            } else if (x < -10 || x > wDp + 10) {
                continue;
            }
            a *= gather;
            if (a <= .02f) continue;
            float px = x * dp, py = y * dp;
            if (s.glows) {
                // The odd larger particle: a soft round glow around a bright centre.
                float r = s.size * 4f * dp;
                glowMatrix.setScale(r, r);
                glowMatrix.postTranslate(px, py);
                glow.getShader().setLocalMatrix(glowMatrix);
                glow.setAlpha(Math.round(a * 255));
                canvas.drawCircle(px, py, r, glow);
                continue;
            }
            int level = Math.min(LEVELS - 1, (int) (a * LEVELS));
            int b = level * 2 + (s.size > .75f ? 1 : 0);
            float[] pts = batch[b];
            pts[batchN[b]++] = px;
            pts[batchN[b]++] = py;
        }
        for (int b = 0; b < batch.length; b++) {
            if (batchN[b] == 0) continue;
            int level = b / 2;
            dot.setAlpha(Math.round((level + .5f) / LEVELS * 255));
            dot.setStrokeWidth((b % 2 == 1 ? 2.2f : 1.4f) * dp);
            canvas.drawPoints(batch[b], 0, batchN[b], dot);
        }

        // Near particles, drawn last because they're "in front".
        for (int i = 0; i < NEAR; i++) {
            double tn = t * NEAR_PACE;
            double cycle = tn / nearLife[i] + nearPhase[i];
            long life = (long) Math.floor(cycle);
            float u = (float) (cycle - life);
            int id = 1000 + i;
            int side = nearSides == 3 ? (hash(id, life, 5) < .5f ? 1 : 2) : nearSides;
            float in = hash(id, life, 6) * NEAR_ZONE;           // how far in from the edge
            float x = side == 1 ? in - 20 : wDp - in + 20;
            // Firefly wander: two slow overlapping sways, plus a gentle drift over its life.
            x += (float) (16 * Math.sin(tn * nearF1[i] + i) + 7 * Math.sin(tn * nearF1[i] * 2.3 + i * 1.7));
            x += (hash(id, life, 7) - .5f) * 40 * u;
            float y = centre(x, t, 0) + nearDy[i] + (hash(id, life, 8) - .5f) * 50
                    + (float) (12 * Math.sin(tn * nearF2[i] + i * 2.1));
            // Slow fade in and out, with a soft flicker.
            float a = (.35f + .3f * hash(id, life, 9)) * Math.min(1, u / .25f) * Math.min(1, (1 - u) / .35f);
            a *= (float) (.82 + .18 * Math.sin(t * 2.6 + i));
            if (scatter > 0) {
                if (scatter >= 1) continue;
                float out = 1 - (1 - scatter) * (1 - scatter) * (1 - scatter);
                x += (side == 1 ? -1 : 1) * 260 * out;   // swept off past their edge
                a *= (1 - scatter) * (1 - scatter);
            } else if (arrive < 1) {
                a *= arrive;
            }
            a *= gather;
            if (a <= .02f) continue;
            float px = x * dp, py = y * dp, r = nearSize[i] * dp;
            glowMatrix.setScale(r, r);
            glowMatrix.postTranslate(px, py);
            bokeh.getShader().setLocalMatrix(glowMatrix);
            bokeh.setAlpha(Math.round(Math.min(1, a) * 255));
            canvas.drawCircle(px, py, r, bokeh);
        }
    }
}
