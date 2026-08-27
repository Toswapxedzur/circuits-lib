package com.minecart.display.render.engine;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Vector3;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Procedural sprite drawing + face→sprite naming — a faithful port of the preview's {@code PreviewTextures}
 * look into the atlas pipeline. Each face sprite bakes the conserved palette dither AND the <b>object-space
 * top-lit gradient</b> ({@link #litFace}) exactly as {@code ModelPreviewApp} did, then folds in that viewer's
 * (near-flat) studio lighting per face. The gradient is measured in the part's OWN space, so it is identical
 * for every instance — bakeable to a fixed PNG and instanced without any runtime lighting (WYSIWYG).
 *
 * <p>Runs offline in {@link SeedPartTextures}; {@link #faceName} keys a sprite by the face's object-space rect
 * + colour, so the seed (what to draw) and the mesh baker (what to look up) can't drift, and identical faces
 * across instances share one sprite.
 */
final class PaletteDither {

    private PaletteDither() {}

    // ---- Series shading, copied from ModelPreviewApp.SHADING + PreviewPart's object-space frame ----
    private static final Vector3 LIGHT = new Vector3(0.5f, 0.7f, 0.4f).nor();
    private static final float SHIFT = 3.5f;
    private static final Vector3 SHADE_CENTER = new Vector3(0f, 2f, 0f);
    private static final float HALF_X = 12.5f, HALF_Y = 3f, HALF_Z = 4.5f;
    private static final float RADIUS = Math.max(1f,
            Math.abs(LIGHT.x) * HALF_X + Math.abs(LIGHT.y) * HALF_Y + Math.abs(LIGHT.z) * HALF_Z);
    // ModelPreviewApp's Environment (ambient + one weak directional), baked per face as a constant multiplier.
    private static final Vector3 AMBIENT = new Vector3(0.93f, 0.93f, 0.96f);
    private static final Vector3 DIR_COLOR = new Vector3(0.14f, 0.14f, 0.15f);
    private static final Vector3 DIR_TO_LIGHT = new Vector3(0.45f, 0.5f, 0.55f).nor(); // -(-0.45,-0.5,-0.55)

    // Known material colours (from Parts) → their preview palette + dither profile.
    private static final Color WHITE = new Color(0.85f, 0.86f, 0.83f, 1f);
    private static final Color STEEL = new Color(0.55f, 0.61f, 0.69f, 1f);

    private static final int[] BAYER4 = {0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5};

    // Per-face outward normal, and the object corners (a,b,c,d) in the SAME order PartMesh emits them.
    private static final int[][] N = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    // Reorder (a,b,c,d) → (p00,p10,p11,p01) so litFace's gradient aligns with the baked FACE_UV in PartMesh.
    private static final int[][] PERM = {
            {1, 2, 3, 0}, {1, 2, 3, 0}, {3, 2, 1, 0}, {0, 1, 2, 3}, {3, 2, 1, 0}, {3, 2, 1, 0}};

    /** A distinct sprite job: one box face. */
    record Face(PartMesh.Box box, int faceId) {}

    /** Stable atlas name for a face: object-space rect (×2 to clear half-integers) + colour + normal. */
    static String faceName(PartMesh.Box b, int f) {
        int[] wh = size(b, f);
        return "f" + hex(b.color())
                + "_" + r2(b.ocx()) + "_" + r2(b.ocy()) + "_" + r2(b.ocz())
                + "_" + Math.round(b.sx()) + "x" + Math.round(b.sy()) + "x" + Math.round(b.sz())
                + "_" + f + "_" + wh[0] + "x" + wh[1];
    }

    /** Every sprite these boxes can request (all 6 faces each; occlusion only ever drops from this set). */
    static Set<Face> faces(List<PartMesh.Box> boxes) {
        Set<Face> out = new LinkedHashSet<>();
        for (PartMesh.Box b : boxes) {
            for (int f = 0; f < 6; f++) {
                out.add(new Face(b, f));
            }
        }
        return out;
    }

    /** In-plane pixel size (== world size) of a face: ±X→(sz,sy), ±Y→(sx,sz), ±Z→(sx,sy). */
    static int[] size(PartMesh.Box b, int f) {
        int sx = Math.round(b.sx()), sy = Math.round(b.sy()), sz = Math.round(b.sz());
        return switch (f) {
            case 0, 1 -> new int[]{sz, sy};
            case 2, 3 -> new int[]{sx, sz};
            default -> new int[]{sx, sy};
        };
    }

    /** Draws one face sprite: object-space lit-palette dither (PreviewTextures.litFace) × the face's studio mul. */
    static Pixmap drawFace(PartMesh.Box b, int f) {
        int[] wh = size(b, f);
        Vector3[] q = objCorners(b, f);
        int[] perm = PERM[f];
        Profile pr = profile(b.color());
        Vector3 mul = faceMul(N[f]);
        long seed = faceName(b, f).hashCode() * 2654435761L;
        return litFace(pr.palette, q[perm[0]], q[perm[1]], q[perm[2]], q[perm[3]], wh[0], wh[1],
                pr.grainMax, pr.zeroWeight, pr.ordered, mul, seed);
    }

    /** The 7-shade plastic ramp for ANY base colour (PreviewTextures.ramp), hue preserved, index 3 == base. */
    static Color[] ramp(Color base) {
        float[] hsv = base.toHsv(new float[3]);
        Color[] out = new Color[7];
        for (int i = 0; i < 7; i++) {
            float t = i / 6f;
            Color c = new Color();
            c.fromHsv(hsv[0], clamp(hsv[1] * (1.20f - 0.40f * t)), clamp(hsv[2] * (0.75f + 0.5f * t)));
            c.a = 1f;
            out[i] = c;
        }
        return out;
    }

    static Color[] grays(int n, float lo, float hi) {
        Color[] c = new Color[n];
        for (int i = 0; i < n; i++) {
            float v = lo + (hi - lo) * i / (n - 1);
            c[i] = new Color(v, v, v, 1f);
        }
        return c;
    }

    /** Steel-blue metal, 5 shades (PreviewTextures.steelBlue). */
    static Color[] steelBlue() {
        return new Color[]{
                new Color(0.30f, 0.35f, 0.42f, 1f),
                new Color(0.43f, 0.49f, 0.57f, 1f),
                new Color(0.55f, 0.61f, 0.69f, 1f),
                new Color(0.68f, 0.75f, 0.84f, 1f),
                new Color(0.84f, 0.91f, 1.00f, 1f),
        };
    }

    private record Profile(Color[] palette, int grainMax, float zeroWeight, boolean ordered) {}

    /** Palette + dither profile per material, matching PreviewPart's box() calls (keyed by the box colour). */
    private static Profile profile(Color c) {
        if (near(c, WHITE)) return new Profile(grays(6, 0.85f, 1.0f), 1, 0.3f, false);   // white band
        if (near(c, STEEL)) return new Profile(steelBlue(), 1, 1.6f, true);              // metal (ordered)
        return new Profile(ramp(c), 2, 0.3f, false);                                     // plastic body/knob
    }

    /**
     * Object-space lit-palette dither (a Pixmap port of PreviewTextures.litFace): each texel's object position
     * (bilinear over the 4 corners) gives a lit value that biases which conserved shade it picks; the result is
     * multiplied by {@code mul} (the face's baked studio lighting). No runtime lighting — this IS the final look.
     */
    private static Pixmap litFace(Color[] palette, Vector3 p00, Vector3 p10, Vector3 p11, Vector3 p01,
                                  int pw, int ph, int grainMax, float zeroWeight, boolean ordered,
                                  Vector3 mul, long seed) {
        int shades = palette.length, mid = shades / 2;
        int peak = Math.max(1, Math.round(grainMax * 0.6f));
        float[] w = new float[grainMax + 1];
        for (int k = 0; k <= grainMax; k++) {
            w[k] = (k == 0) ? zeroWeight : 1f / (1f + Math.abs(k - peak));
        }
        long state = seed | 1L;
        Pixmap pm = new Pixmap(pw, ph, Pixmap.Format.RGBA8888);
        for (int py = 0; py < ph; py++) {
            for (int px = 0; px < pw; px++) {
                float u = (px + 0.5f) / pw, v = (py + 0.5f) / ph;
                float wx = lerp(lerp(p00.x, p10.x, u), lerp(p01.x, p11.x, u), v);
                float wy = lerp(lerp(p00.y, p10.y, u), lerp(p01.y, p11.y, u), v);
                float wz = lerp(lerp(p00.z, p10.z, u), lerp(p01.z, p11.z, u), v);
                float proj = (wx - SHADE_CENTER.x) * LIGHT.x + (wy - SHADE_CENTER.y) * LIGHT.y
                        + (wz - SHADE_CENTER.z) * LIGHT.z;
                float lit = clamp(0.5f + 0.5f * proj / RADIUS);
                float cIdx = mid + (lit - 0.5f) * SHIFT;
                int idx;
                if (ordered) {
                    float b = (BAYER4[(py & 3) * 4 + (px & 3)] + 0.5f) / 16f;
                    idx = (int) Math.floor(cIdx + b);
                } else {
                    state = xorshift(state);
                    int g = pickStep(w, grainMax, (state >>> 40) / (float) (1L << 24));
                    state = xorshift(state);
                    int sign = ((state >>> 40) & 1L) == 0 ? -1 : 1;
                    idx = Math.round(cIdx) + sign * g;
                }
                idx = Math.max(0, Math.min(shades - 1, idx));
                Color c = palette[idx];
                pm.setColor(clamp(c.r * mul.x), clamp(c.g * mul.y), clamp(c.b * mul.z), 1f);
                pm.drawPixel(px, py);
            }
        }
        return pm;
    }

    /** The 4 object-space corners of face f (a,b,c,d), matching PartMesh's world-corner order exactly. */
    private static Vector3[] objCorners(PartMesh.Box b, int f) {
        float x0 = b.ocx() - b.sx() / 2f, x1 = b.ocx() + b.sx() / 2f;
        float y0 = b.ocy() - b.sy() / 2f, y1 = b.ocy() + b.sy() / 2f;
        float z0 = b.ocz() - b.sz() / 2f, z1 = b.ocz() + b.sz() / 2f;
        return switch (f) {
            case 0 -> new Vector3[]{v(x1, y0, z0), v(x1, y1, z0), v(x1, y1, z1), v(x1, y0, z1)};   // +X
            case 1 -> new Vector3[]{v(x0, y0, z1), v(x0, y1, z1), v(x0, y1, z0), v(x0, y0, z0)};   // -X
            case 2 -> new Vector3[]{v(x0, y1, z1), v(x1, y1, z1), v(x1, y1, z0), v(x0, y1, z0)};   // +Y
            case 3 -> new Vector3[]{v(x0, y0, z0), v(x1, y0, z0), v(x1, y0, z1), v(x0, y0, z1)};   // -Y
            case 4 -> new Vector3[]{v(x0, y0, z1), v(x1, y0, z1), v(x1, y1, z1), v(x0, y1, z1)};   // +Z
            default -> new Vector3[]{v(x1, y0, z0), v(x0, y0, z0), v(x0, y1, z0), v(x1, y1, z0)};  // -Z
        };
    }

    /** The face's fixed studio-light multiplier: ambient + weak directional·normal (from ModelPreviewApp). */
    private static Vector3 faceMul(int[] n) {
        float ndl = Math.max(0f, n[0] * DIR_TO_LIGHT.x + n[1] * DIR_TO_LIGHT.y + n[2] * DIR_TO_LIGHT.z);
        return new Vector3(clamp(AMBIENT.x + DIR_COLOR.x * ndl),
                clamp(AMBIENT.y + DIR_COLOR.y * ndl), clamp(AMBIENT.z + DIR_COLOR.z * ndl));
    }

    private static boolean near(Color a, Color b) {
        return Math.abs(a.r - b.r) < 0.01f && Math.abs(a.g - b.g) < 0.01f && Math.abs(a.b - b.b) < 0.01f;
    }

    private static Vector3 v(float x, float y, float z) {
        return new Vector3(x, y, z);
    }

    private static int r2(float v) {
        return Math.round(v * 2f);
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

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

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

    private static long xorshift(long s) {
        s ^= s << 13; s ^= s >>> 7; s ^= s << 17;
        return s;
    }
}
