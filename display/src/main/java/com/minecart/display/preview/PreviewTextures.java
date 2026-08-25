package com.minecart.display.preview;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.math.Vector3;

/**
 * Procedural plastic textures for the model preview. The surface is drawn from a <b>small hand-tuned
 * palette</b> (5–7 entries) and the palette is <b>conserved</b> — only those exact colours ever appear.
 *
 * <p><b>Shading via palette bias:</b> each texel is generated in world space; the local <i>lit value</i>
 * (how much a surface point faces the light) shifts the <i>mean</i> palette index up (brighter) or down
 * (darker), and a small medium-offset grain is added on top. So light raises the <i>probability</i> a pixel
 * is a brighter shade rather than blending it off-palette — the shading is dithered across the 7 colours.
 * Textures are generated per face at 1 texel = 1 world unit (crisp, non-tiling) so the gradient spans the part.
 */
final class PreviewTextures {

    private PreviewTextures() {}

    /** Vanilla lime-wool green, 7 hand-tuned shades (slightly wider): base lime with a brighter pale-yellow top. */
    static Color[] limeWool() {
        return new Color[]{
                new Color(0.44f, 0.67f, 0.12f, 1f),
                new Color(0.49f, 0.73f, 0.13f, 1f),
                new Color(0.53f, 0.78f, 0.15f, 1f),
                new Color(0.57f, 0.82f, 0.16f, 1f), // ~ base lime-wool
                new Color(0.62f, 0.87f, 0.20f, 1f),
                new Color(0.70f, 0.92f, 0.28f, 1f),
                new Color(0.82f, 0.99f, 0.43f, 1f), // brightest: pale, brighter + yellower
        };
    }

    /** Metallic steel, 5 shades: deep blue-grey → light steel-blue highlight (cool, reads as brushed metal). */
    static Color[] steelBlue() {
        return new Color[]{
                new Color(0.30f, 0.35f, 0.42f, 1f),
                new Color(0.43f, 0.49f, 0.57f, 1f),
                new Color(0.55f, 0.61f, 0.69f, 1f),
                new Color(0.68f, 0.75f, 0.84f, 1f),
                new Color(0.84f, 0.91f, 1.00f, 1f), // light-blue highlight
        };
    }

    /** {@code n} grayscale shades in [{@code lo},{@code hi}] — a neutral tile a material tints via diffuse. */
    static Color[] grays(int n, float lo, float hi) {
        Color[] c = new Color[n];
        for (int i = 0; i < n; i++) {
            float v = lo + (hi - lo) * i / (n - 1);
            c[i] = new Color(v, v, v, 1f);
        }
        return c;
    }

    /**
     * Generates one face texture (1 texel = 1 world unit). Each texel's world position is bilinearly
     * interpolated from the face's four world corners; its <i>lit value</i> shifts the mean palette index,
     * and a medium-offset grain is added — so all pixels stay on the palette while the light biases them
     * brighter/darker.
     *
     * @param palette   conserved shade colours, dark → bright
     * @param p00,p10,p11,p01  world corners matching UV (0,0),(1,0),(1,1),(0,1)
     * @param pw,ph     texture size in texels (== the face's world size, rounded)
     * @param center    reference point the lit gradient is measured from
     * @param lightDir  unit light direction (object space)
     * @param radius    world half-extent the gradient spans
     * @param shift     how many palette indices the lit value swings the mean across the part
     * @param grainMax  medium-offset grain magnitude on top of the lit mean
     * @param seed      fixes the pattern
     */
    // 4×4 Bayer matrix for ordered dithering — spreads a gradient across shades in a fine regular pattern
    // (no random salt-and-pepper, no hard bands), so neighbours differ by at most one shade.
    private static final int[] BAYER4 = {
            0, 8, 2, 10,
            12, 4, 14, 6,
            3, 11, 1, 9,
            15, 7, 13, 5,
    };

    static Texture litFace(Color[] palette,
                           Vector3 p00, Vector3 p10, Vector3 p11, Vector3 p01,
                           int pw, int ph, Vector3 center, Vector3 lightDir,
                           float radius, float shift, int grainMax, float zeroWeight, boolean ordered, long seed) {
        int shades = palette.length;
        int mid = shades / 2;
        int peak = Math.max(1, Math.round(grainMax * 0.6f));
        // zeroWeight is the weight of "no offset" (follow the lit gradient exactly). Higher -> weaker scatter,
        // more pixels track the lighting, so a bright pixel's neighbours stay bright (fewer bright-next-to-dark).
        float[] w = new float[grainMax + 1];
        for (int k = 0; k <= grainMax; k++) {
            w[k] = (k == 0) ? zeroWeight : 1f / (1f + Math.abs(k - peak));
        }

        long state = seed | 1L;
        Pixmap pm = new Pixmap(pw, ph, Pixmap.Format.RGBA8888);
        for (int py = 0; py < ph; py++) {
            for (int px = 0; px < pw; px++) {
                float u = (px + 0.5f) / pw, v = (py + 0.5f) / ph;
                // bilinear world position across the face
                float wx = lerp(lerp(p00.x, p10.x, u), lerp(p01.x, p11.x, u), v);
                float wy = lerp(lerp(p00.y, p10.y, u), lerp(p01.y, p11.y, u), v);
                float wz = lerp(lerp(p00.z, p10.z, u), lerp(p01.z, p11.z, u), v);
                float proj = (wx - center.x) * lightDir.x + (wy - center.y) * lightDir.y + (wz - center.z) * lightDir.z;
                float lit = 0.5f + 0.5f * proj / radius;
                lit = Math.max(0f, Math.min(1f, lit));

                float cIdx = mid + (lit - 0.5f) * shift;
                int idx;
                if (ordered) {
                    // Ordered dither: floor(cIdx + bayer) interleaves the two nearest shades by the Bayer
                    // threshold -> smooth gradient, neighbours at most one shade apart, no clusters.
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

                pm.setColor(palette[idx]);
                pm.drawPixel(px, py);
            }
        }
        Texture t = new Texture(pm);
        t.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        t.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
        pm.dispose();
        return t;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** Weighted pick of a grain magnitude 0..max from a 0..1 random. */
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
