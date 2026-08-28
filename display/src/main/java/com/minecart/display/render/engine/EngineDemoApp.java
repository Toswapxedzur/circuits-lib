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
 * Instanced component engine demo. Everything is listed <b>once, in order</b> along a single row:
 * the blank base board in each of the 11 plastic colours (red→pink), then the capacitor (3 sizes), a green
 * slide switch, press switch, resistor, diode, LED, then the azure wire family (every size state). Inspect
 * freely with a fly camera — <b>WASD</b> to move, <b>Space</b>/<b>Shift</b> up/down, drag to look, scroll for
 * speed. The switch/press buttons animate on a timer. Run with {@code ./gradlew :display:enginedemo}.
 */
public final class EngineDemoApp extends ApplicationAdapter {

    private static final int GREEN = 3;      // lime — the switches' colour
    private static final float SPACING = 34f; // X spacing between listed parts
    private static final int[] LED_COLORS = {0, 2, 3, 5, 7, 8}; // LED bulb colours: red, yellow, lime, cyan, blue, violet
    private static final int COUNT = Parts.PLASTIC_HSV.length + Parts.CAP_SIZES.length + 5 + LED_COLORS.length; // bases + 3 caps + slide/press/resistor/diode/lamp + LEDs

    private PerspectiveCamera cam;
    private FlyController fly;
    private EngineRenderer engine;
    private final List<ComponentInstance> switches = new ArrayList<>();
    private final List<ComponentInstance> pressers = new ArrayList<>();
    private float clock;

    /** Total row length of the appended wire family (each wire body + its 13px gap). */
    private static float wireSpan() {
        float t = 0f;
        for (int n = Parts.WIRE_MIN; n <= Parts.WIRE_MAX; n++) {
            t += 13f + (n - 1) * 12f + 9f;
        }
        return t;
    }

    /** The demo board slab under the row. Shared with {@link SeedPartTextures} so its sprites are generated too. */
    static PartMesh.Box boardBox() {
        float w = COUNT * SPACING + 30f + wireSpan(); // classic slots + the wire family off the right end
        PaletteDither.Paint paint = new PaletteDither.Paint(
                PaletteDither.ramp(new Color(0.16f, 0.18f, 0.22f, 1f)), Color.WHITE, // dark board so teal parts read
                2, 0.3f, false, 900L, wireSpan() / 2f, -1f, 0f, w / 2f, 1f);
        return PartMesh.Box.local(wireSpan() / 2f, -1f, 0f, w, 2f, 49f, paint);
    }

    @Override
    public void create() {
        ModelLoader loader = new ModelLoader(); // load parts from committed JSON (datagen output), not code
        engine = new EngineRenderer();

        String green = Parts.PLASTIC_NAME[GREEN];        // "lime" — the switches' colour
        String[] capNames = {"big", "medium", "small"};
        float mid = (COUNT - 1) / 2f;
        Matrix4 world = new Matrix4();
        int i = 0;
        for (String name : Parts.PLASTIC_NAME) {                          // blank base board in every colour
            world.setToTranslation((i++ - mid) * SPACING, 0f, 0f);
            engine.add(new ComponentInstance(loader.model("base_" + name), world));
        }
        for (String size : capNames) {                                   // capacitor, the 3 sizes (teal)
            world.setToTranslation((i++ - mid) * SPACING, 0f, 0f);
            engine.add(new ComponentInstance(loader.model("capacitor_" + size), world));
        }
        world.setToTranslation((i++ - mid) * SPACING, 0f, 0f);           // green slide switch
        ComponentInstance sw = new ComponentInstance(loader.model("switch_" + green), world);
        sw.anim.channel("slide", 0f, 1f, 6f);
        switches.add(sw);
        engine.add(sw);
        world.setToTranslation((i++ - mid) * SPACING, 0f, 0f);           // green press switch
        ComponentInstance ps = new ComponentInstance(loader.model("press_" + green), world);
        ps.anim.channel("press", 0f, 1f, 9f);
        pressers.add(ps);
        engine.add(ps);
        world.setToTranslation((i++ - mid) * SPACING, 0f, 0f);           // yellow resistor (raised, tilted leads)
        engine.add(new ComponentInstance(loader.model("resistor_yellow"), world));
        world.setToTranslation((i++ - mid) * SPACING, 0f, 0f);           // yellow diode (black blob, arrowed red trace)
        engine.add(new ComponentInstance(loader.model("diode_yellow"), world));
        world.setToTranslation((i++ - mid) * SPACING, 0f, 0f);           // lamp (white-encased warm bulb)
        engine.add(new ComponentInstance(loader.model("lamp"), world));
        for (int c : LED_COLORS) {                                       // LEDs: ONE greyscale bulb, many entity colours
            world.setToTranslation((i++ - mid) * SPACING, 0f, 0f);
            Color tint = new Color().fromHsv(Parts.PLASTIC_HSV[c][0], Parts.PLASTIC_HSV[c][1], Parts.PLASTIC_HSV[c][2]);
            tint.a = 1f;
            engine.add(new ComponentInstance(loader.model("led_" + Parts.PLASTIC_NAME[c]), world,
                    new ComponentEntity(tint)));
        }

        float wx = (COUNT - 1 - mid) * SPACING + 16.5f;                  // right edge of the classic row
        for (int n = Parts.WIRE_MIN; n <= Parts.WIRE_MAX; n++) {         // the wire family, one per size state
            float w = (n - 1) * 12f + 9f;
            wx += 13f + w / 2f;                                          // gap, then this wire's centre
            world.setToTranslation(wx, 0f, 0f);
            engine.add(new ComponentInstance(loader.model("wire_" + n), world));
            wx += w / 2f;
        }

        engine.addStatic(List.of(boardBox()));
        engine.build();

        float reach = (COUNT * SPACING + wireSpan()) / 2f;
        cam = new PerspectiveCamera(60f, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        cam.position.set(wireSpan() / 2f, reach * 0.62f, reach * 0.95f); // overview of the full row incl. wires
        cam.near = 0.5f;
        cam.far = 8000f;
        cam.lookAt(wireSpan() / 2f, 4f, 0f);
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
