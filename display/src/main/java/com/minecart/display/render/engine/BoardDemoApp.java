package com.minecart.display.render.engine;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.minecart.display.snap.SnapModelBridge;
import com.minecart.snap.AllSnapParts;
import com.minecart.snap.Facing;
import com.minecart.snap.SnapBoard;

/**
 * Pivot Phase-B keystone demo: a real {@link SnapBoard} rendered in 3D through the instanced engine, via the
 * pure {@link SnapModelBridge} + the {@link SnapBoardScene} adapter — the exact pipeline the 3D {@code SnapScreen}
 * will use. Parts are placed on the unified P=24 grid in several headings (EAST/NORTH/WEST/SOUTH) and on stacked
 * layers, to show the bridge's yaw + layer transforms carry through the engine's dynamic-entity path. Inspect
 * with the fly camera — <b>WASD</b> move, <b>Space</b>/<b>Shift</b> up/down, drag to look. Run:
 * {@code ./gradlew :display:boarddemo}.
 */
public final class BoardDemoApp extends ApplicationAdapter {

    private PerspectiveCamera cam;
    private FlyController fly;
    private EngineRenderer engine;
    private ModelLoader loader;
    private SnapBoard board;
    private int lastRevision;
    private float clock;
    private boolean blinkOn;

    /** A small hand-built board: a wire run that turns a corner, a cross branch, and a battery→resistor stack. */
    private static SnapBoard demoBoard() {
        AllSnapParts.init();
        SnapBoard board = SnapBoard.createDefault();
        // An L-shaped wire run: three cells EAST along row 1, then two cells NORTH up column 4.
        board.place(AllSnapParts.SNAP_WIRE, 1, 1, 0, Facing.EAST);
        board.place(AllSnapParts.SNAP_WIRE, 2, 1, 0, Facing.EAST);
        board.place(AllSnapParts.SNAP_WIRE, 3, 1, 0, Facing.EAST);
        board.place(AllSnapParts.SNAP_WIRE, 4, 1, 0, Facing.NORTH);
        board.place(AllSnapParts.SNAP_WIRE, 4, 2, 0, Facing.NORTH);
        // A branch heading the other ways so every heading (and its yaw) is represented.
        board.place(AllSnapParts.SNAP_WIRE, 4, 3, 0, Facing.WEST);
        board.place(AllSnapParts.SNAP_WIRE, 2, 3, 0, Facing.SOUTH);
        // A functional stack: a battery on the base with a resistor stacked on top (a V/R loop).
        board.place(AllSnapParts.SNAP_BATTERY, 1, 4, 0, Facing.EAST, 5.0);
        board.place(AllSnapParts.SNAP_RESISTOR, 1, 4, 1, Facing.EAST, 10.0);
        // A lone resistor pointing NORTH for orientation contrast.
        board.place(AllSnapParts.SNAP_RESISTOR, 5, 4, 0, Facing.NORTH, 22.0);
        return board;
    }

    @Override
    public void create() {
        loader = new ModelLoader();
        engine = new EngineRenderer();

        board = demoBoard();
        lastRevision = board.revision();
        SnapBoardScene.populate(engine, loader, board.placements());

        // (No board slab: a slab's sprites would need a datagen seed pass for its exact dimensions; the parts'
        // own sprites are committed, so they render on the clear background — enough for the pipeline proof.)
        float p = SnapModelBridge.PITCH;
        float cx = 3f * p, cz = 2.5f * p;
        engine.build();

        cam = new PerspectiveCamera(60f, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        cam.position.set(cx - 90f, 130f, cz + 150f);
        cam.near = 0.5f;
        cam.far = 4000f;
        cam.lookAt(cx, 2f, cz);
        cam.up.set(0f, 1f, 0f);
        cam.update();
        fly = new FlyController(cam, 160f);
        Gdx.input.setInputProcessor(fly);
    }

    @Override
    public void render() {
        float dt = Math.min(Gdx.graphics.getDeltaTime(), 1f / 30f);
        fly.update(dt);

        // Live-edit proof: blink a wire in/out on a timer, then rebuild the engine from the board whenever its
        // revision advances — the same revision-driven refresh the real 3D SnapScreen will run on edits.
        clock += dt;
        if (clock >= 1.5f) {
            clock = 0f;
            blinkOn = !blinkOn;
            if (blinkOn) {
                board.place(AllSnapParts.SNAP_WIRE, 0, 5, 0, Facing.EAST); // a free edge (0,5)->(1,5)
            } else {
                board.remove(new com.minecart.snap.Post(0, 5, 0), new com.minecart.snap.Post(1, 5, 0));
            }
        }
        int rev = board.revision();                 // read revision BEFORE the snapshot (see SnapBoardScene#rebuild)
        if (rev != lastRevision) {
            lastRevision = rev;
            SnapBoardScene.rebuild(engine, loader, board.placements());
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
        config.setTitle("Board Demo — a real SnapBoard rendered via SnapModelBridge (WASD, drag to look)");
        config.setWindowedMode(1200, 760);
        config.setBackBufferConfig(8, 8, 8, 8, 16, 0, 4);
        config.setOpenGLEmulation(Lwjgl3ApplicationConfiguration.GLEmulation.GL30, 3, 2);
        config.setForegroundFPS(60);
        new Lwjgl3Application(new BoardDemoApp(), config);
    }
}
