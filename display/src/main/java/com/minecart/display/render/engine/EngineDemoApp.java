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
 * Skeleton demo of the instanced component engine: a grid mixing static capacitors and slide switches, drawn
 * as one instanced call per part-type (all studs across the whole grid = a single call). The switches' knobs
 * slide via each component's {@link AnimationState} — proving the "instance moves on user action" path (here
 * driven by a timer instead of input). Run with {@code ./gradlew :display:enginedemo}.
 */
public final class EngineDemoApp extends ApplicationAdapter {

    private static final int GRID = 12;
    private static final float SPACING = 40f;

    private PerspectiveCamera cam;
    private CameraInputController camCtl;
    private Parts parts;
    private EngineRenderer engine;
    private final List<ComponentInstance> switches = new ArrayList<>();
    private float clock;

    /** The demo board slab. Shared with {@link SeedPartTextures} so its sprites get generated too. */
    static PartMesh.Box boardBox() {
        float span = GRID * SPACING;
        return PartMesh.Box.local(0f, -1f, 0f, span + 60f, 2f, span + 60f, new Color(0.16f, 0.18f, 0.22f, 1f));
    }

    @Override
    public void create() {
        parts = new Parts();
        engine = new EngineRenderer();

        float mid = (GRID - 1) / 2f;
        Matrix4 world = new Matrix4();
        for (int i = 0; i < GRID; i++) {
            for (int j = 0; j < GRID; j++) {
                world.setToTranslation((i - mid) * SPACING, 0f, (j - mid) * SPACING);
                if (j % 2 == 0) {
                    engine.add(new ComponentInstance(parts.capacitor, world));
                } else {
                    ComponentInstance sw = new ComponentInstance(parts.slideSwitch, world);
                    sw.anim.channel("slide", 0f, 1f, 6f); // slider slides ±1 in the 4-wide well, eases at 6 units/s
                    switches.add(sw);
                    engine.add(sw);
                }
            }
        }

        // A board slab under the grid: every component's bottom face is now neighbour-culled against its top.
        engine.addStatic(List.of(boardBox()));
        engine.build(); // stitch the atlas + bake the neighbour-culled static scene mesh (rebuild on a board edit)

        float reach = mid * SPACING;
        cam = new PerspectiveCamera(55f, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        cam.position.set(reach * 0.8f, reach * 1.1f, reach * 1.5f);
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
