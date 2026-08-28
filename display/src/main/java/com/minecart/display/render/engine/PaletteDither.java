package com.minecart.display.render.engine;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Vector3;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Sprite drawing + face→sprite naming — a <b>pixel-exact</b> port of {@code PreviewTextures.litFace} as used by
 * {@code PreviewPart}/{@code ModelPreviewApp}. Each face is drawn with that part's EXACT paint ({@link Paint}:
 * palette, grain, seed, object-space shade centre/radius) and the SAME corner order + seed formula
 * ({@code 0x9E37_0000 + (seedBase + faceId + 1) * 2654435761}), then multiplied by the viewer's studio light
 * (ambient + weak directional) folded per face — so a baked sprite equals what the preview rendered, texel for
 * texel. The shading is object-space (identical for every instance), so it bakes to a fixed PNG and instances.
 */
final class PaletteDither {

    private PaletteDither() {}

    // Series light + gradient (ModelPreviewApp.SHADING + PreviewPart's object-space frame).
    private static final Vector3 LIGHT = new Vector3(0.5f, 0.7f, 0.4f).nor();
    private static final float SHIFT = 3.5f;
    // ModelPreviewApp's Environment, baked per face as a constant multiplier (× the material diffuse tint).
    private static final Vector3 AMBIENT = new Vector3(0.93f, 0.93f, 0.96f);
    private static final Vector3 DIR_COLOR = new Vector3(0.14f, 0.14f, 0.15f);
    private static final Vector3 DIR_TO_LIGHT = new Vector3(0.45f, 0.5f, 0.55f).nor(); // -(-0.45,-0.5,-0.55)

    private static final int[] BAYER4 = {0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5};
    private static final int[][] NRM = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /** How one box's faces are painted — mirrors a PreviewPart {@code box(...)} call's texture args.
     *  {@code alpha} &lt; 1 bakes a translucent sprite (for glass-like parts drawn in the blended pass). */
    record Paint(Color[] palette, Color diffuse, int grainMax, float zeroWeight, boolean ordered,
                 long seedBase, float scx, float scy, float scz, float radius, float alpha) {}

    /** A distinct sprite job: one box face. */
    record Face(PartMesh.Box box, int faceId) {}

    /** Stable atlas name: everything that determines the face's litFace output (so identical faces dedupe). */
    static String faceName(PartMesh.Box b, int f) {
        Paint p = b.paint();
        int[] wh = size(b, f);
        Vector3[] q = objCorners(b, f);
        long h = 1125899906842597L;
        h = h * 31 + p.seedBase(); h = h * 31 + f;
        h = h * 31 + p.grainMax(); h = h * 31 + Float.floatToIntBits(p.zeroWeight());
        h = h * 31 + (p.ordered() ? 1 : 0);
        h = h * 31 + Float.floatToIntBits(p.alpha());
        for (Color c : p.palette()) h = h * 31 + Color.rgba8888(c);
        h = h * 31 + Color.rgba8888(p.diffuse());
        for (Vector3 v : q) {
            h = h * 31 + Math.round((v.x - p.scx()) * 2f);
            h = h * 31 + Math.round((v.y - p.scy()) * 2f);
            h = h * 31 + Math.round((v.z - p.scz()) * 2f);
        }
        if (f == 2 && b.trace() != null) { // the trace decal is printed only on the top (+Y) face
            h = h * 31 + Color.rgba8888(b.trace().color());
            h = h * 31 + (b.trace().capacitor() ? 1 : 0);
            h = h * 31 + (b.trace().arrow() ? 1 : 0);
            if (b.trace().span() != PartMesh.Trace.DEFAULT_SPAN) { // folded in only when non-default, so every
                h = h * 31 + Float.floatToIntBits(b.trace().span()); // pre-span sprite name stays byte-stable
            }
        }
        return "sp" + Long.toHexString(h & 0x7fffffffffffffffL) + "_" + wh[0] + "x" + wh[1];
    }

    /**
     * Every sprite these boxes can request: all 6 faces each, MINUS any degenerate (zero-area) face — a
     * 0-thickness box (a flat leg) only has its two large faces. Occlusion drops further from this set.
     */
    static Set<Face> faces(List<PartMesh.Box> boxes) {
        Set<Face> out = new LinkedHashSet<>();
        for (PartMesh.Box b : boxes) {
            for (int f = 0; f < 6; f++) {
                int[] wh = size(b, f);
                if (wh[0] > 0 && wh[1] > 0) out.add(new Face(b, f));
            }
        }
        return out;
    }

    /**
     * Sprite size in texels (== world units): pw along the face's U edge (a→b), ph along V (a→d) — matching
     * {@link #objCorners} and PartMesh's CORNER_UV. ±X→(sy,sz), ±Y→(sx,sz), ±Z→(sx,sy). Getting ±X wrong
     * (transposed) stretched the small end-cap faces.
     */
    static int[] size(PartMesh.Box b, int f) {
        int sx = Math.round(b.sx()), sy = Math.round(b.sy()), sz = Math.round(b.sz());
        return switch (f) {
            case 0, 1 -> new int[]{sy, sz};
            case 2, 3 -> new int[]{sx, sz};
            default -> new int[]{sx, sy};
        };
    }

    /** Draws one face sprite exactly as PreviewPart did: litFace over the object corners × the studio light. */
    static Pixmap drawFace(PartMesh.Box b, int f) {
        Paint p = b.paint();
        int[] wh = size(b, f);
        Vector3[] q = objCorners(b, f); // p00,p10,p11,p01 — same order PreviewPart passes to litFace
        long seed = 0x9E370000L + (p.seedBase() + f + 1) * 2654435761L;
        Vector3 mul = faceMul(NRM[f], p.diffuse());
        Pixmap pm = litFace(p.palette(), q[0], q[1], q[2], q[3], wh[0], wh[1],
                p.scx(), p.scy(), p.scz(), p.radius(), p.grainMax(), p.zeroWeight(), p.ordered(), mul, seed, p.alpha());
        if (f == 2 && b.trace() != null) { // print the trace on top — dithered like the plastics, never flat
            Pixmap tp = litFace(ramp(b.trace().color()), q[0], q[1], q[2], q[3], wh[0], wh[1],
                    p.scx(), p.scy(), p.scz(), p.radius(), p.grainMax(), p.zeroWeight(), p.ordered(), mul,
                    seed + 7, p.alpha());
            overlayTrace(pm, tp, b, b.trace(), wh[0], wh[1]);
            tp.dispose();
        }
        return pm;
    }

    /** Prints the TRACE decal onto the (already-drawn) top-face pixmap: for each texel on the trace/symbol,
     *  copy the texel from {@code tp} — a full litFace pass over the SAME face in the trace colour's ramp — so
     *  the printed line carries the same conserved-palette dither + lighting as every other surface (owner
     *  rule: no pure colours anywhere). Each decorated box draws only its portion of the shape. */
    private static void overlayTrace(Pixmap pm, Pixmap tp, PartMesh.Box b, PartMesh.Trace trace, int pw, int ph) {
        float x0 = b.ocx() - b.sx() / 2f, x1 = b.ocx() + b.sx() / 2f;
        float z1 = b.ocz() + b.sz() / 2f, sz = b.sz();
        for (int py = 0; py < ph; py++) {
            float z = z1 - ((py + 0.5f) / ph) * sz;             // +Y face: v runs z1 → z0
            for (int px = 0; px < pw; px++) {
                float x = x0 + ((px + 0.5f) / pw) * (x1 - x0);  // u runs x0 → x1
                if (onTrace(x, z, trace)) pm.drawPixel(px, py, tp.getPixel(px, py));
            }
        }
    }

    /** Trace shape in a part's object frame: a 1-wide line at z=0 over x∈[−span, span] (span 10.5 on the
     *  standard ±12-stud body; the wire family passes its own); a capacitor breaks the middle and adds two
     *  7-long plates (perpendicular), each exactly 1px on the INTEGER texel-centre grid at x=−2 and x=+3
     *  (centre-to-centre 5 — the owner's asymmetric pick: a half-integer centre like ±2.5 straddles TWO texel
     *  centres and fattens the stroke to 2px, which is banned); {@code arrow} (the diode, currently unused)
     *  adds an arrowhead pointing in the flow direction (+x) — a chevron whose two arms extend 3px
     *  back-and-out from the tip texel at span−0.5 (arm pixels (9,±1)(8,±2)(7,±3)), overlaid on the line. */
    private static boolean onTrace(float x, float z, PartMesh.Trace t) {
        float span = t.span();
        boolean line = Math.abs(z) <= 0.5f && Math.abs(x) <= span;
        if (t.arrow() && Math.abs(z) >= 0.5f && Math.abs(z) <= 3.5f
                && Math.abs(x - (span - 0.5f - Math.abs(z))) <= 0.5f) return true;
        if (!t.capacitor()) return line;
        boolean plate = (Math.abs(x + 2f) < 0.5f || Math.abs(x - 3f) < 0.5f) && Math.abs(z) <= 3.5f;
        return (line && (x < -1.5f || x > 2.5f)) || plate; // line stops at the plates; gap texels −1..2
    }

    /** Stable atlas name for an oriented {@link PartMesh.Quad} (its object corners + size determine the pixels). */
    static String quadName(PartMesh.Quad q) {
        Paint p = q.paint();
        Vector3[] corners = {q.o00(), q.o10(), q.o11(), q.o01()};
        long h = 1125899906842597L;
        h = h * 31 + p.seedBase(); h = h * 31 + 6; // 6 = quad marker (past the 6 box faces)
        h = h * 31 + p.grainMax(); h = h * 31 + Float.floatToIntBits(p.zeroWeight());
        h = h * 31 + (p.ordered() ? 1 : 0);
        h = h * 31 + Float.floatToIntBits(p.alpha());
        for (Color c : p.palette()) h = h * 31 + Color.rgba8888(c);
        h = h * 31 + Color.rgba8888(p.diffuse());
        for (Vector3 v : corners) {
            h = h * 31 + Math.round((v.x - p.scx()) * 2f);
            h = h * 31 + Math.round((v.y - p.scy()) * 2f);
            h = h * 31 + Math.round((v.z - p.scz()) * 2f);
        }
        return "qd" + Long.toHexString(h & 0x7fffffffffffffffL) + "_" + q.pw() + "x" + q.ph();
    }

    /** Draws an oriented quad's sprite: litFace over its object corners × the studio light for its normal. */
    static Pixmap drawQuad(PartMesh.Quad q) {
        Paint p = q.paint();
        long seed = 0x9E370000L + (p.seedBase() + 6 + 1) * 2654435761L;
        Vector3 e1 = new Vector3(q.o10()).sub(q.o00());
        Vector3 e2 = new Vector3(q.o01()).sub(q.o00());
        Vector3 n = e2.crs(e1).nor(); // up-out face normal (matches the p00,p10,p11,p01 winding)
        Vector3 mul = faceMul(n.x, n.y, n.z, p.diffuse());
        return litFace(p.palette(), q.o00(), q.o10(), q.o11(), q.o01(), q.pw(), q.ph(),
                p.scx(), p.scy(), p.scz(), p.radius(), p.grainMax(), p.zeroWeight(), p.ordered(), mul, seed, p.alpha());
    }

    // ---- palettes (verbatim from PreviewTextures) ----

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

    static Color[] rampHsv(float h, float s, float v) {
        Color base = new Color();
        base.fromHsv(h, s, v);
        base.a = 1f;
        return ramp(base);
    }

    static Color[] grays(int n, float lo, float hi) {
        Color[] c = new Color[n];
        for (int i = 0; i < n; i++) {
            float v = lo + (hi - lo) * i / (n - 1);
            c[i] = new Color(v, v, v, 1f);
        }
        return c;
    }

    static Color[] steelBlue() {
        return new Color[]{
                new Color(0.30f, 0.35f, 0.42f, 1f),
                new Color(0.43f, 0.49f, 0.57f, 1f),
                new Color(0.55f, 0.61f, 0.69f, 1f),
                new Color(0.68f, 0.75f, 0.84f, 1f),
                new Color(0.84f, 0.91f, 1.00f, 1f),
        };
    }

    /** Object-space lit-palette dither — a Pixmap port of PreviewTextures.litFace, × the per-face {@code mul}. */
    private static Pixmap litFace(Color[] palette, Vector3 p00, Vector3 p10, Vector3 p11, Vector3 p01,
                                  int pw, int ph, float scx, float scy, float scz, float radius,
                                  int grainMax, float zeroWeight, boolean ordered, Vector3 mul, long seed, float alpha) {
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
                float proj = (wx - scx) * LIGHT.x + (wy - scy) * LIGHT.y + (wz - scz) * LIGHT.z;
                float lit = clamp(0.5f + 0.5f * proj / radius);
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
                pm.setColor(clamp(c.r * mul.x), clamp(c.g * mul.y), clamp(c.b * mul.z), alpha);
                pm.drawPixel(px, py);
            }
        }
        return pm;
    }

    /** The 4 object-space corners (p00,p10,p11,p01) of face f — the SAME order PreviewPart passes to litFace. */
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

    private static Vector3 faceMul(int[] n, Color diffuse) {
        return faceMul(n[0], n[1], n[2], diffuse);
    }

    private static Vector3 faceMul(float nx, float ny, float nz, Color diffuse) {
        float ndl = Math.max(0f, nx * DIR_TO_LIGHT.x + ny * DIR_TO_LIGHT.y + nz * DIR_TO_LIGHT.z);
        return new Vector3(clamp((AMBIENT.x + DIR_COLOR.x * ndl) * diffuse.r),
                clamp((AMBIENT.y + DIR_COLOR.y * ndl) * diffuse.g),
                clamp((AMBIENT.z + DIR_COLOR.z * ndl) * diffuse.b));
    }

    private static Vector3 v(float x, float y, float z) {
        return new Vector3(x, y, z);
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
