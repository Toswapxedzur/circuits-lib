package com.minecart.display.render.engine;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Disposable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The public seam that renders <b>every committed part model in a square grid</b> — the merged "texture
 * displayer". It hides the package-private engine ({@link EngineRenderer}, {@link ModelLoader}, {@link
 * ComponentInstance}) behind a small API so both the in-game debug mode ({@code ModelGalleryScreen}) and the
 * standalone {@code ModelWorldApp} share ONE implementation.
 *
 * <p><b>Robust to concurrent datagen.</b> Other agents may be regenerating models/textures while this builds,
 * so every model is loaded in its own try/catch (a malformed/partial JSON is skipped, not fatal) and is only
 * placed once every sprite it references exists on disk (a model whose textures aren't generated yet is
 * skipped). {@link #build()} may be called again to re-scan.
 *
 * <p>The tiny movable sub-part TYPES (slider/button/pointer) and the unit scenery slab are excluded — they are
 * bits and blobs, not standalone components.
 */
public final class ModelGalleryView implements Disposable {

    private static final float SPACING = 44f; // grid pitch (parts are ~33 wide; leaves a clear gap)
    private static final Set<String> EXCLUDED = Set.of("slider", "button", "pointer", "slab");

    private EngineRenderer engine;
    private float lx = 0.5f, ly = 0.7071f, lz = 0.5f; // skylight dir → baked octant (default NE, keeps base sprite names)
    private float gridReach = 200f;

    /** Sets the baked-skylight direction (picks the octant sprite variant at {@link #build()}). */
    public void setLightDir(float x, float y, float z) {
        lx = x; ly = y; lz = z;
        if (engine != null) engine.setLightDir(x, y, z);
    }

    /** (Re)discover every model, load the ready non-excluded ones, lay them in a square grid, and bake. */
    public void build() {
        if (engine != null) engine.dispose();
        engine = new EngineRenderer();
        engine.setLightDir(lx, ly, lz);
        ModelLoader loader = new ModelLoader();

        List<ComponentModel> ready = new ArrayList<>();
        for (String id : discover()) {
            if (EXCLUDED.contains(id)) continue;
            try {
                ComponentModel m = loader.model(id);
                if (spritesReady(m)) ready.add(m);
            } catch (Exception | AssertionError ignored) {
                // not loadable this pass (partial JSON, missing dep) — skip, never fatal
            }
        }
        int cols = Math.max(1, (int) Math.ceil(Math.sqrt(ready.size())));
        Matrix4 world = new Matrix4();
        for (int i = 0; i < ready.size(); i++) {
            int c = i % cols, r = i / cols;
            world.setToTranslation((c - (cols - 1) / 2f) * SPACING, 0f, (r - (cols - 1) / 2f) * SPACING);
            engine.add(new ComponentInstance(ready.get(i), world));
        }
        gridReach = Math.max(120f, cols * SPACING);
        try {
            engine.build();
        } catch (Exception e) {   // last-resort guard — a torn sprite that slipped past the existence check
            Gdx.app.error("gallery", "build failed, showing an empty grid this pass: " + e.getMessage());
            engine.dispose();
            engine = new EngineRenderer();
            engine.setLightDir(lx, ly, lz);
            engine.build();
        }
        Gdx.app.log("gallery", "laid out " + ready.size() + " models in a " + cols + "×grid");
    }

    /** Half-extent of the grid — a good camera distance / fly speed. */
    public float gridReach() {
        return gridReach;
    }

    public void render(Camera cam) {
        if (engine == null) return;
        engine.update(Gdx.graphics.getDeltaTime());
        engine.render(cam);
    }

    @Override
    public void dispose() {
        if (engine != null) engine.dispose();
    }

    /** Every {@code models/parts/*.json} basename, sorted. Reads the on-disk source dir (a classpath directory
     *  can't be enumerated on LWJGL3) so a re-scan sees whatever the other agents have produced. */
    static List<String> discover() {
        List<String> ids = new ArrayList<>();
        FileHandle dir = modelsDir();
        if (dir != null) {
            for (FileHandle f : dir.list()) {
                String n = f.name();
                if (n.endsWith(".json")) ids.add(n.substring(0, n.length() - ".json".length()));
            }
        }
        Collections.sort(ids);
        return ids;
    }

    private static FileHandle modelsDir() {
        for (String p : new String[]{"display/src/main/resources/models/parts", "src/main/resources/models/parts"}) {
            FileHandle f = Gdx.files.local(p);
            if (f.exists() && f.isDirectory()) return f;
        }
        return null;
    }

    /** True only if EVERY sprite this model (and its movable part-types) references is already a PNG on disk. */
    static boolean spritesReady(ComponentModel m) {
        Set<String> names = new LinkedHashSet<>();
        PartMesh.collectSpriteNames(m.staticBoxes, m.staticQuads, names);
        for (ComponentModel.MovablePart mp : m.movableParts) {
            PartMesh.collectSpriteNames(mp.type().boxes(), List.of(), names);
        }
        for (String n : names) {
            if (!Gdx.files.internal("textures/parts/" + n + ".png").exists()) return false;
        }
        return true;
    }
}
