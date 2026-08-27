package com.minecart.display.render.engine;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.g3d.utils.CameraInputController;
import com.badlogic.gdx.math.Matrix4;

import java.util.ArrayList;
import java.util.List;

/**
 * Demo of the instanced component engine as a <b>colour chart</b>: one column per plastic body colour
 * ({@link Parts#PLASTIC_HSV}), a capacitor in the front row and a slide switch in the back row. Drawn as one
 * instanced call per movable part-type (every slider across the chart = a single call); the switches' knobs
 * slide via each component's {@link AnimationState}. Run with {@code ./gradlew :display:enginedemo}.
 */
public final class EngineDemoApp extends ApplicationAdapter {

    private static final int COLS = Parts.PLASTIC_HSV.length; // 11 body colours
    private static final int GREEN = 3;                       // lime — the switch's own colour
    private static final float COL_SP = 40f;                  // X spacing between colours
    private static final float ROW_SP = 34f;                  // Z spacing between the capacitor and switch rows

    private PerspectiveCamera cam;
    private CameraInputController camCtl;
    private Parts parts;
    private EngineRenderer engine;
    private final List<ComponentInstance> switches = new ArrayList<>();
    private float clock;

    /** The demo board slab. Shared with {@link SeedPartTextures} so its sprites get generated too. */
    static PartMesh.Box boardBox() {
        // Board backdrop: a flat dark plastic (own shade centre + large radius → near-flat, no runtime light).
        PaletteDither.Paint paint = new PaletteDither.Paint(
                PaletteDither.ramp(new Color(0.16f, 0.18f, 0.22f, 1f)), Color.WHITE,
                2, 0.3f, false, 900L, 0f, -1f, 0f, 400f);
        float w = COLS * COL_SP + 40f, d = ROW_SP + 80f;
        return PartMesh.Box.local(0f, -1f, 0f, w, 2f, d, paint);
    }

    @Override
    public void create() {
        parts = new Parts();
        engine = new EngineRenderer();

        float midCol = (COLS - 1) / 2f;
        Matrix4 world = new Matrix4();
        for (int c = 0; c < COLS; c++) {
            float x = (c - midCol) * COL_SP;
            world.setToTranslation(x, 0f, -ROW_SP / 2f); // front row: capacitor — one per body colour
            engine.add(new ComponentInstance(parts.capacitors[c], world));

            world.setToTranslation(x, 0f, ROW_SP / 2f);  // back row: slide switch — always green (its own colour)
            ComponentInstance sw = new ComponentInstance(parts.switches[GREEN], world);
            sw.anim.channel("slide", 0f, 1f, 6f); // slider slides ±1 in the 4-wide well, eases at 6 units/s
            switches.add(sw);
            engine.add(sw);
        }

        // A board slab under the chart: every component's bottom face is neighbour-culled against its top.
        engine.addStatic(List.of(boardBox()));
        engine.build(); // stitch the atlas + bake the neighbour-culled static scene mesh (rebuild on a board edit)

        float reach = COLS * COL_SP / 2f;
        cam = new PerspectiveCamera(55f, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        cam.position.set(0f, reach * 0.85f, reach * 1.15f);
        cam.near = 1f;
        cam.far = 6000f;
        cam.lookAt(0f, 0f, 0f);
        cam.update();
        camCtl = new CameraInputController(cam);
        camCtl.translateUnits = reach * 3f;
        Gdx.input.setInputProcessor(camCtl);
    }

    @Override
    public void render() {
        camCtl.update();
        float dt = Math.min(Gdx.graphics.getDeltaTime(), 1f / 30f);
        clock += dt;
        // Ping-pong every ~1.5s: flip the slide target for all switches (user-action stand-in).
        float slideTarget = ((int) (clock / 1.5f) % 2 == 0) ? 1f : -1f;
        for (ComponentInstance sw : switches) {
            sw.anim.target("slide", slideTarget);
        }
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
        config.setTitle("Engine Demo — instanced components");
        config.setWindowedMode(1200, 760);
        config.setBackBufferConfig(8, 8, 8, 8, 16, 0, 4);
        config.setOpenGLEmulation(Lwjgl3ApplicationConfiguration.GLEmulation.GL30, 3, 2); // instancing needs GL3+
        config.setForegroundFPS(60);
        new Lwjgl3Application(new EngineDemoApp(), config);
    }
}
