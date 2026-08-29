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

    private static final int PAD = 1; // 1px extruded border around each sprite (Nearest-safe, keeps 1:1)

    private final Texture texture;
    private final Map<String, Region> regions = new HashMap<>();

    PartAtlas(Collection<String> spriteNames, PaletteDither.Octant octant) {
        // Load every referenced sprite PNG (fixed, committed), for the current skylight OCTANT — each name has a
        // committed <name>_<octant>.png variant (see SeedPartTextures). Regions are keyed by the BASE name, so the
        // mesh baker requests the octant-independent name and gets the lit variant. Distinct names only.
        List<String> names = new ArrayList<>();
        List<Pixmap> pixmaps = new ArrayList<>();
        for (String name : new java.util.LinkedHashSet<>(spriteNames)) {
            FileHandle f = Gdx.files.internal("textures/parts/" + name + octant.suffix + ".png");
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

        // Each sprite occupies (w+2*PAD)×(h+2*PAD) — a 1px extruded border around it, so Nearest sampling at a
        // face's exact edge lands on a copy of the edge texel (no bleed) while the UVs still map to texel EDGES
        // (1 texel = 1 world unit exactly — even pixels, no mixels).
        int widest = 0;
        for (Pixmap p : pixmaps) widest = Math.max(widest, p.getWidth());
        int atlasW = nextPow2(Math.max(64, widest + 2 * PAD));

        int[] px = new int[names.size()]; // top-left of the sprite CONTENT (inside its border)
        int[] py = new int[names.size()];
        int x = 0, y = 0, shelfH = 0;
        for (int oi : order) {
            Pixmap p = pixmaps.get(oi);
            int cw = p.getWidth() + 2 * PAD, ch = p.getHeight() + 2 * PAD;
            if (x + cw > atlasW) {
                x = 0;
                y += shelfH;
                shelfH = 0;
            }
            px[oi] = x + PAD;
            py[oi] = y + PAD;
            x += cw;
            shelfH = Math.max(shelfH, ch);
        }
        int atlasH = nextPow2(y + shelfH);

        Pixmap page = new Pixmap(atlasW, atlasH, Pixmap.Format.RGBA8888);
        page.setBlending(Pixmap.Blending.None);
        for (int i = 0; i < names.size(); i++) {
            Pixmap p = pixmaps.get(i);
            int ox = px[i], oy = py[i], w = p.getWidth(), h = p.getHeight();
            page.drawPixmap(p, ox, oy);
            // Extrude the 4 edges + corners into the 1px border.
            page.drawPixmap(p, ox - 1, oy, 0, 0, 1, h);         // left
            page.drawPixmap(p, ox + w, oy, w - 1, 0, 1, h);     // right
            page.drawPixmap(p, ox, oy - 1, 0, 0, w, 1);         // top
            page.drawPixmap(p, ox, oy + h, 0, h - 1, w, 1);     // bottom
            page.drawPixmap(p, ox - 1, oy - 1, 0, 0, 1, 1);     // TL
            page.drawPixmap(p, ox + w, oy - 1, w - 1, 0, 1, 1); // TR
            page.drawPixmap(p, ox - 1, oy + h, 0, h - 1, 1, 1); // BL
            page.drawPixmap(p, ox + w, oy + h, w - 1, h - 1, 1, 1); // BR
            // Texel-edge UVs → face UV 0..1 spans exactly the w×h texels (pixel-perfect).
            regions.put(names.get(i), new Region(
                    ox / (float) atlasW, oy / (float) atlasH,
                    (ox + w) / (float) atlasW, (oy + h) / (float) atlasH));
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
