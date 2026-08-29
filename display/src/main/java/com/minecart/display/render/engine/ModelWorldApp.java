package com.minecart.display.render.engine;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.math.Matrix4;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A <b>model test world</b>: it discovers EVERY committed component model ({@code models/parts/*.json}) and lays
 * them out in a square grid so you can eyeball the whole catalogue at once. Run: {@code ./gradlew :display:modelworld}.
 *
 * <p><b>Robust to concurrent datagen.</b> Other agents may be regenerating models/textures at the same time, so
 * a model may be half-written, or its JSON may exist before its sprites do. This world therefore <b>never trusts
 * a model blindly</b>: each is loaded in its own try/catch (a malformed/partial JSON is skipped, not fatal), and
 * before a model is placed its every referenced sprite PNG is checked to exist (a model whose textures aren't
 * generated yet is skipped). So an in-progress model is simply left out of this pass instead of crashing the
 * world. Press <b>R</b> to re-scan and rebuild once the other agents have finished (each launch re-copies the
 * resources, so relaunching also picks up newly added models). Fly with WASD + Space/Shift, drag to look.
 */
public final class ModelWorldApp extends ApplicationAdapter {

    private static final float SPACING = 44f; // grid pitch (parts are ~33 wide; leaves a clear gap)

    private PerspectiveCamera cam;
    private FlyController fly;
    private EngineRenderer engine;
    private float gridReach = 200f;

    @Override
    public void create() {
        cam = new PerspectiveCamera(60f, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        cam.near = 0.5f;
        cam.far = 20000f;
        cam.up.set(0f, 1f, 0f);
        rebuild();
        cam.position.set(0f, gridReach * 1.3f, gridReach * 1.3f);
        cam.lookAt(0f, 0f, 0f);
        cam.update();
        fly = new FlyController(cam, gridReach);
        Gdx.input.setInputProcessor(fly);
    }

    /** (Re)discover every model, load the ready ones, and lay them in a square grid. Skips anything not ready. */
    private void rebuild() {
        if (engine != null) engine.dispose();
        engine = new EngineRenderer();
        ModelLoader loader = new ModelLoader(); // fresh loader → fresh cache, so a re-scan re-reads changed files

        List<String> ids = discover();
        List<ComponentModel> ready = new ArrayList<>();
        int broken = 0;
        for (String id : ids) {
            try {
                ComponentModel m = loader.model(id);
                if (spritesReady(m)) ready.add(m);
                else { broken++; Gdx.app.log("modelworld", "skip (textures not generated yet): " + id); }
            } catch (Exception | AssertionError e) {   // partial/malformed JSON, missing part-type dep, etc.
                broken++;
                Gdx.app.log("modelworld", "skip (not loadable): " + id + " — " + e.getMessage());
            }
        }

        int cols = Math.max(1, (int) Math.ceil(Math.sqrt(ready.size())));
        Matrix4 world = new Matrix4();
        for (int i = 0; i < ready.size(); i++) {
            int c = i % cols, r = i / cols;
            float x = (c - (cols - 1) / 2f) * SPACING;
            float z = (r - (cols - 1) / 2f) * SPACING;
            world.setToTranslation(x, 0f, z);
            engine.add(new ComponentInstance(ready.get(i), world));
        }
        gridReach = Math.max(120f, cols * SPACING);

        try {
            engine.build();
        } catch (Exception e) {   // last-resort guard: a torn sprite that slipped past the existence check
            Gdx.app.error("modelworld", "build failed, showing an empty world this pass: " + e.getMessage());
            engine.dispose();
            engine = new EngineRenderer();
            engine.build();
        }
        Gdx.app.log("modelworld", "listed " + ready.size() + " / " + ids.size()
                + " models in a " + cols + "×" + (int) Math.ceil(ready.size() / (float) cols) + " grid ("
                + broken + " not ready)");
    }

    /** Every {@code models/parts/*.json} basename, sorted. Empty (not fatal) if the directory can't be found. */
    private static List<String> discover() {
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

    /**
     * The real (listable) models directory. A classpath {@code internal} dir does NOT enumerate on LWJGL3 (only
     * individual files load), so we point at the source resources folder on disk — the SAME folder datagen
     * writes to, so a re-scan (R) sees whatever the other agents have produced. Tries both working-dir layouts
     * (root and the :display module); returns null if neither exists.
     */
    private static FileHandle modelsDir() {
        for (String p : new String[]{"display/src/main/resources/models/parts", "src/main/resources/models/parts"}) {
            FileHandle f = Gdx.files.local(p);
            if (f.exists() && f.isDirectory()) return f;
        }
        return null;
    }

    /** True only if EVERY sprite this model (and its movable part-types) references is already a PNG on disk. */
    private static boolean spritesReady(ComponentModel m) {
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

    @Override
    public void render() {
        float dt = Math.min(Gdx.graphics.getDeltaTime(), 1f / 30f);
        fly.update(dt);
        if (Gdx.input.isKeyJustPressed(Input.Keys.R)) rebuild();
        engine.update(dt);
        Gdx.gl.glClearColor(0.11f, 0.12f, 0.15f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        engine.render(cam);
    }

    @Override
    public void resize(int width, int height) {
        cam.viewportWidth = width;
        cam.viewportHeight = height;
        cam.update();
    }

    @Override
    public void dispose() {
        engine.dispose();
    }

    public static void main(String[] args) {
        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("Model World — every model in a square grid (R = re-scan)");
        config.setWindowedMode(1280, 800);
        config.setBackBufferConfig(8, 8, 8, 8, 16, 0, 4);
        config.setOpenGLEmulation(Lwjgl3ApplicationConfiguration.GLEmulation.GL30, 3, 2); // instancing needs GL3+
        config.setForegroundFPS(60);
        new Lwjgl3Application(new ModelWorldApp(), config);
    }
}
