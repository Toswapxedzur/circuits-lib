package com.minecart.display.render.engine;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;

/**
 * One shared, tiling grayscale <b>dither</b> tile for the whole engine: each texel is a quantised gray level
 * (a small palette), chosen independently at a medium offset from mid — the "crafted grain, no TV-static, no
 * clusters" look. A face's UVs tile it at 1 texel = 1 world unit (pixel-perfect), and the shader multiplies it
 * into the box's flat base colour, so every material gets the plastic grain from a single texture (keeping the
 * scene to one draw call). The per-material hue-shifted litFace palettes are a later atlas step.
 */
final class EngineTextures {

    private EngineTextures() {}

    static Texture dither() {
        int size = 32;
        int shades = 6;
        float lo = 0.82f, hi = 1.02f;
        float[] pal = new float[shades];
        for (int i = 0; i < shades; i++) {
            pal[i] = lo + (hi - lo) * i / (shades - 1);
        }
        int mid = shades / 2;
        float[] w = {0.35f, 1.0f, 0.5f}; // offset 0/1/2 weights — peak at a medium step
        float total = w[0] + w[1] + w[2];

        long state = 0x9E3779B97F4A7C15L;
        Pixmap pm = new Pixmap(size, size, Pixmap.Format.RGBA8888);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                state = xorshift(state);
                float r = (state >>> 40) / (float) (1L << 24) * total;
                int m = r < w[0] ? 0 : r < w[0] + w[1] ? 1 : 2;
                state = xorshift(state);
                int sign = ((state >>> 40) & 1L) == 0 ? -1 : 1;
                int idx = Math.max(0, Math.min(shades - 1, mid + sign * m));
                float g = Math.min(1f, pal[idx]);
                pm.setColor(g, g, g, 1f);
                pm.drawPixel(x, y);
            }
        }
        Texture t = new Texture(pm);
        t.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        t.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);
        pm.dispose();
        return t;
    }

    private static long xorshift(long s) {
        s ^= s << 13; s ^= s >>> 7; s ^= s << 17;
        return s;
    }
}
