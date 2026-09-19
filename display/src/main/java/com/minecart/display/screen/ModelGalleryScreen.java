package com.minecart.display.screen;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.math.Vector3;
import com.minecart.display.DisplayApp;
import com.minecart.display.input.FreeCameraController;
import com.minecart.display.render.engine.ModelGalleryView;

/**
 * The in-game <b>debug component gallery</b>: lays out every component TYPE exactly once (the curated
 * {@link com.minecart.display.snap.SnapModelBridge#placeableIds() catalogue}, one tile per part — no colour or
 * size variants), on a single flat plane, via {@link ModelGalleryView}. No board, no server, no game logic.
 * Reached by joining a save created in {@link com.minecart.foundation.GameMode#DEBUG_MODELS}. WASD + Space/Shift
 * to move, drag to look, <b>R</b> to rebuild, <b>Esc</b> to leave. (The all-models "texture displayer" that
 * shows every colour/size variant lives on as the standalone {@code :display:modelworld} task.)
 */
public final class ModelGalleryScreen extends ScreenAdapter {

    private final DisplayApp app;
    private final ModelGalleryView gallery = new ModelGalleryView();
    private PerspectiveCamera camera;
    private FreeCameraController flyCam;
    private final String shotPath = System.getProperty("snap.uishot"); // dev: screenshot a few frames in, then exit
    private int frame;

    public ModelGalleryScreen(DisplayApp app) {
        this.app = app;
    }

    @Override
    public void show() {
        // DEV: -Dsnap.skylight=ne|nw|se|sw overrides the baked skylight octant (match SnapScreen's convention).
        String sky = System.getProperty("snap.skylight");
        if (sky != null) {
            float sx = sky.contains("w") ? -0.5f : 0.5f, sz = sky.contains("s") ? -0.5f : 0.5f;
            gallery.setLightDir(sx, 0.7071f, sz);
        }
        gallery.build(com.minecart.display.snap.SnapModelBridge.placeableIds());
        float reach = gallery.gridReach();

        camera = new PerspectiveCamera(60f, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        camera.near = 1f;
        camera.far = Math.max(8000f, reach * 12f);
        Vector3 start = new Vector3(0f, reach * 0.62f, reach * 0.78f); // high 3/4 view that frames the whole grid
        flyCam = new FreeCameraController(camera, start, new Vector3(0f, 0f, 0f), reach);
        flyCam.setLookEnabled(true);

        Gdx.input.setInputProcessor(new InputAdapter() {
            @Override
            public boolean keyDown(int keycode) {
                if (keycode == Keys.ESCAPE) {
                    app.setScreen(new WorldListScreen(app));
                    return true;
                }
                if (keycode == Keys.R) {   // rebuild (reload the catalogue models)
                    gallery.build(com.minecart.display.snap.SnapModelBridge.placeableIds());
                    return true;
                }
                return false;
            }
        });
    }

    @Override
    public void render(float dt) {
        flyCam.update(dt);
        camera.update();
        Gdx.gl.glClearColor(0.11f, 0.12f, 0.15f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        gallery.render(camera);

        if (shotPath != null && ++frame == 25) {
            try {
                com.badlogic.gdx.graphics.Pixmap p = com.badlogic.gdx.graphics.Pixmap.createFromFrameBuffer(
                        0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
                com.badlogic.gdx.graphics.PixmapIO.writePNG(Gdx.files.absolute(shotPath), p, -1, true);
                p.dispose();
                Gdx.app.log("gallery", "screenshot -> " + shotPath);
            } catch (Exception e) {
                Gdx.app.error("gallery", "screenshot failed", e);
            }
            Gdx.app.exit();
        }
    }

    @Override
    public void resize(int width, int height) {
        if (camera != null) {
            camera.viewportWidth = width;
            camera.viewportHeight = height;
            camera.update();
        }
    }

    @Override
    public void dispose() {
        gallery.dispose();
    }
}
