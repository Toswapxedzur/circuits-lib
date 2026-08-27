package com.minecart.display.render.engine;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The texture atlas — the Minecraft-style "map". Loads each referenced sprite from a fixed committed PNG
 * ({@code textures/parts/<name>.png}, made once by {@link SeedPartTextures}) and stitches them into ONE
 * {@link Texture} with a simple shelf packer. A face bakes UVs straight into the atlas region for its sprite,
 * so the whole scene samples this single texture — one draw call preserved. Nearest + no runtime post: what
 * the map holds is exactly what renders.
 */
final class PartAtlas implements Disposable {

    /** A sprite's rectangle in the atlas, as UVs with a half-texel inset (Nearest-safe, no neighbour bleed). */
    record Region(float u0, float v0, float u1, float v1) {}

    private static final int GAP = 1; // 1px gutter between sprites

    private final Texture texture;
    private final Map<String, Region> regions = new HashMap<>();

    PartAtlas(Collection<String> spriteNames) {
        // Load every referenced sprite PNG (fixed, committed). Distinct names only.
        List<String> names = new ArrayList<>();
        List<Pixmap> pixmaps = new ArrayList<>();
        for (String name : new java.util.LinkedHashSet<>(spriteNames)) {
            FileHandle f = Gdx.files.internal("textures/parts/" + name + ".png");
            if (!f.exists()) {
                throw new GdxRuntimeException("Missing sprite PNG: " + f.path()
                        + " — run ./gradlew :display:seedtextures first.");
            }
            names.add(name);
            pixmaps.add(new Pixmap(f));
        }

        // Shelf-pack (tallest first) into a power-of-two page wide enough for the widest sprite.
        Integer[] order = new Integer[names.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> pixmaps.get(b).getHeight() - pixmaps.get(a).getHeight());

        int widest = 0;
        for (Pixmap p : pixmaps) widest = Math.max(widest, p.getWidth());
        int atlasW = nextPow2(Math.max(64, widest + 2 * GAP));

        int[] px = new int[names.size()];
        int[] py = new int[names.size()];
        int x = GAP, y = GAP, shelfH = 0;
        for (int oi : order) {
            Pixmap p = pixmaps.get(oi);
            if (x + p.getWidth() + GAP > atlasW) {
                x = GAP;
                y += shelfH + GAP;
                shelfH = 0;
            }
            px[oi] = x;
            py[oi] = y;
            x += p.getWidth() + GAP;
            shelfH = Math.max(shelfH, p.getHeight());
        }
        int atlasH = nextPow2(y + shelfH + GAP);

        Pixmap page = new Pixmap(atlasW, atlasH, Pixmap.Format.RGBA8888);
        page.setBlending(Pixmap.Blending.None);
        for (int i = 0; i < names.size(); i++) {
            Pixmap p = pixmaps.get(i);
            page.drawPixmap(p, px[i], py[i]);
            // Half-texel inset so Nearest sampling at u/v = 0 or 1 stays inside this sprite.
            regions.put(names.get(i), new Region(
                    (px[i] + 0.5f) / atlasW, (py[i] + 0.5f) / atlasH,
                    (px[i] + p.getWidth() - 0.5f) / atlasW, (py[i] + p.getHeight() - 0.5f) / atlasH));
            p.dispose();
        }

        texture = new Texture(page);
        texture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        texture.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
        page.dispose();
    }

    Region region(String name) {
        Region r = regions.get(name);
        if (r == null) {
            throw new GdxRuntimeException("Sprite not in atlas: " + name);
        }
        return r;
    }

    Texture texture() {
        return texture;
    }

    private static int nextPow2(int v) {
        int p = 1;
        while (p < v) p <<= 1;
        return p;
    }

    @Override
    public void dispose() {
        texture.dispose();
    }
}
