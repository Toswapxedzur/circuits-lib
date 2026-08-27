package com.minecart.display.render.engine;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.math.Matrix4;

import java.util.ArrayList;
import java.util.List;

/**
 * Instanced component engine demo. Everything is listed <b>once, in order</b> along a single row: the capacitor
 * in each of the 11 plastic colours (red→pink), then a green slide switch, then a green press switch. Inspect
 * freely with a fly camera — <b>WASD</b> to move, <b>Space</b>/<b>Shift</b> up/down, drag to look, scroll for
 * speed. The switch/press buttons animate on a timer. Run with {@code ./gradlew :display:enginedemo}.
 */
public final class EngineDemoApp extends ApplicationAdapter {

    private static final int GREEN = 3;      // lime — the switches' colour
    private static final float SPACING = 34f; // X spacing between listed parts
    private static final int COUNT = Parts.PLASTIC_HSV.length + 2; // 11 capacitors + slide + press

    private PerspectiveCamera cam;
    private FlyController fly;
    private Parts parts;
    private EngineRenderer engine;
    private final List<ComponentInstance> switches = new ArrayList<>();
    private final List<ComponentInstance> pressers = new ArrayList<>();
    private float clock;

    /** The demo board slab under the row. Shared with {@link SeedPartTextures} so its sprites are generated too. */
    static PartMesh.Box boardBox() {
        PaletteDither.Paint paint = new PaletteDither.Paint(
                PaletteDither.ramp(new Color(0.16f, 0.18f, 0.22f, 1f)), Color.WHITE,
                2, 0.3f, false, 900L, 0f, -1f, 0f, 400f);
        float w = COUNT * SPACING + 30f;
        return PartMesh.Box.local(0f, -1f, 0f, w, 2f, 49f, paint);
    }

    @Override
    public void create() {
        parts = new Parts();
        engine = new EngineRenderer();

        float mid = (COUNT - 1) / 2f;
        Matrix4 world = new Matrix4();
        int i = 0;
        for (int c = 0; c < Parts.PLASTIC_HSV.length; c++, i++) {
            world.setToTranslation((i - mid) * SPACING, 0f, 0f);
            engine.add(new ComponentInstance(parts.capacitors[c], world)); // capacitor, one per colour
        }
        world.setToTranslation((i++ - mid) * SPACING, 0f, 0f);             // green slide switch
        ComponentInstance sw = new ComponentInstance(parts.switches[GREEN], world);
        sw.anim.channel("slide", 0f, 1f, 6f);
        switches.add(sw);
        engine.add(sw);
        world.setToTranslation((i - mid) * SPACING, 0f, 0f);              // green press switch
        ComponentInstance ps = new ComponentInstance(parts.pressSwitches[GREEN], world);
        ps.anim.channel("press", 0f, 1f, 9f);
        pressers.add(ps);
        engine.add(ps);

        engine.addStatic(List.of(boardBox()));
        engine.build();

        float reach = COUNT * SPACING / 2f;
        cam = new PerspectiveCamera(60f, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        cam.position.set(0f, reach * 0.5f, reach * 0.8f);
        cam.near = 0.5f;
        cam.far = 8000f;
        cam.lookAt(0f, 3f, 0f);
        cam.up.set(0f, 1f, 0f);
        cam.update();
        fly = new FlyController(cam, reach * 0.9f);
        Gdx.input.setInputProcessor(fly);
    }

    @Override
    public void render() {
        float dt = Math.min(Gdx.graphics.getDeltaTime(), 1f / 30f);
        clock += dt;
        fly.update(dt);

        float slideTarget = ((int) (clock / 1.5f) % 2 == 0) ? 1f : -1f;
        for (ComponentInstance s : switches) {
            s.anim.target("slide", slideTarget);
        }
        float pressTarget = ((int) (clock / 1.1f) % 2 == 0) ? 1f : 0f;
        for (ComponentInstance p : pressers) {
            p.anim.target("press", pressTarget);
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
        config.setTitle("Engine Demo — instanced components (WASD + Space/Shift, drag to look)");
        config.setWindowedMode(1200, 760);
        config.setBackBufferConfig(8, 8, 8, 8, 16, 0, 4);
        config.setOpenGLEmulation(Lwjgl3ApplicationConfiguration.GLEmulation.GL30, 3, 2); // instancing needs GL3+
        config.setForegroundFPS(60);
        new Lwjgl3Application(new EngineDemoApp(), config);
    }
}
