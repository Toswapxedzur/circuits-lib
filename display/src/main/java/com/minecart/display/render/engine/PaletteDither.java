package com.minecart.display.render.engine;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Procedural sprite drawing + face→sprite naming. This is the ONLY place the plastic look is generated, and
 * it runs <b>offline</b> ({@link SeedPartTextures}): each sprite is drawn once to a fixed PNG and from then on
 * the atlas just shows those exact texels (no runtime dither, no lighting — WYSIWYG, like Minecraft).
 *
 * <p>A face's sprite is keyed by its box colour and its in-plane pixel size, so faces of the same colour+size
 * share one authored PNG. {@link #faceSprite} is used by both the seed generator (what to draw) and the mesh
 * baker (what to look up), so the two can't drift.
 */
final class PaletteDither {

    private PaletteDither() {}

    /** A distinct sprite to draw: a colour at an exact pixel size. */
    record Spec(Color color, int w, int h) {}

    /** The atlas sprite name for a face of {@code color} whose in-plane pixel size is {@code w}×{@code h}. */
    static String faceSprite(Color color, int w, int h) {
        return "tile_" + hex(color) + "_" + w + "x" + h;
    }

    static String name(Spec s) {
        return faceSprite(s.color(), s.w(), s.h());
    }

    /** Every sprite the given boxes can request: each box contributes its (≤3) distinct face sizes. */
    static Set<Spec> specs(List<PartMesh.Box> boxes) {
        Set<Spec> out = new LinkedHashSet<>();
        for (PartMesh.Box b : boxes) {
            int sx = Math.round(b.sx()), sy = Math.round(b.sy()), sz = Math.round(b.sz());
            out.add(new Spec(b.color(), sx, sz)); // ±Y (top/bottom)
            out.add(new Spec(b.color(), sz, sy)); // ±X
            out.add(new Spec(b.color(), sx, sy)); // ±Z
        }
        return out;
    }

    /**
     * Draws one "base plastic block" sprite in the agreed look: the colour's conserved 7-shade {@link #ramp}
     * (hand-tuned HSV, index 3 = base, hue preserved) dithered with a medium-offset grain around the mid shade
     * — the same {@code grainMax=2, zeroWeight=0.3} scatter {@code PreviewTextures.litFace} used for plastic
     * bodies, minus the world-space gradient (a shared per-(colour,size) sprite can't carry a per-face gradient;
     * this is the flat base tile). Deterministic per (colour,size) so re-seeding is stable.
     */
    static Pixmap drawTile(Spec s) {
        int w = s.w(), h = s.h();
        Color[] pal = ramp(s.color());
        int shades = pal.length, mid = shades / 2;
        int grainMax = 2;
        float zeroWeight = 0.3f;
        int peak = Math.max(1, Math.round(grainMax * 0.6f)); // == 1
        float[] wt = new float[grainMax + 1];
        for (int k = 0; k <= grainMax; k++) {
            wt[k] = (k == 0) ? zeroWeight : 1f / (1f + Math.abs(k - peak));
        }

        long state = seed(s.color(), w, h);
        Pixmap pm = new Pixmap(w, h, Pixmap.Format.RGBA8888);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                state = xorshift(state);
                int g = pickStep(wt, grainMax, (state >>> 40) / (float) (1L << 24));
                state = xorshift(state);
                int sign = ((state >>> 40) & 1L) == 0 ? -1 : 1;
                pm.setColor(pal[Math.max(0, Math.min(shades - 1, mid + sign * g))]);
                pm.drawPixel(x, y);
            }
        }
        return pm;
    }

    /**
     * The agreed 7-shade plastic ramp for ANY base colour (from {@code PreviewTextures.ramp}): darker,
     * slightly-more-saturated low shades rising to a brighter, slightly pastel highlight, hue preserved (built
     * in HSV so it never washes toward a wrong colour). Index 3 == the base; low overall contrast.
     */
    static Color[] ramp(Color base) {
        float[] hsv = base.toHsv(new float[3]);
        Color[] out = new Color[7];
        for (int i = 0; i < 7; i++) {
            float t = i / 6f;
            float v = clamp(hsv[2] * (0.75f + 0.5f * t));
            float sat = clamp(hsv[1] * (1.20f - 0.40f * t));
            Color c = new Color();
            c.fromHsv(hsv[0], sat, v);
            c.a = 1f;
            out[i] = c;
        }
        return out;
    }

    /** Weighted pick of a grain magnitude 0..max from a 0..1 random (matches PreviewTextures.pickStep). */
    private static int pickStep(float[] w, int max, float r) {
        float total = 0f;
        for (float value : w) total += value;
        float t = r * total;
        for (int k = 0; k <= max; k++) {
            t -= w[k];
            if (t <= 0f) return k;
        }
        return max;
    }

    private static String hex(Color c) {
        return String.format("%02x%02x%02x", to255(c.r), to255(c.g), to255(c.b));
    }

    private static int to255(float v) {
        return Math.max(0, Math.min(255, Math.round(v * 255f)));
    }

    private static float clamp(float v) {
        return v < 0f ? 0f : v > 1f ? 1f : v;
    }

    /** Stable per-sprite seed so the same (colour,size) always draws the same grain. */
    private static long seed(Color c, int w, int h) {
        long s = 0x9E3779B97F4A7C15L;
        s = s * 31 + Float.floatToIntBits(c.r);
        s = s * 31 + Float.floatToIntBits(c.g);
        s = s * 31 + Float.floatToIntBits(c.b);
        s = s * 31 + w;
        s = s * 31 + h;
        return s == 0 ? 1 : s;
    }

    private static long xorshift(long s) {
        s ^= s << 13; s ^= s >>> 7; s ^= s << 17;
        return s;
    }
}
