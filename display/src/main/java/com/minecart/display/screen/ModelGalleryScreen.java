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
 * The in-game <b>debug model gallery</b> (the merged "texture displayer"): lays out every committed part model
 * in a square grid via {@link ModelGalleryView}, fly-camera to inspect. Reached by joining a save created in
 * {@link com.minecart.foundation.GameMode#DEBUG_MODELS} — client-only, no board, no server. WASD + Space/Shift
 * to move, drag to look, <b>R</b> to re-scan (pick up models other agents just produced), <b>Esc</b> to leave.
 */
public final class ModelGalleryScreen extends ScreenAdapter {

    private final DisplayApp app;
    private final ModelGalleryView gallery = new ModelGalleryView();
    private PerspectiveCamera camera;
    private FreeCameraController flyCam;

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
        gallery.build();
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
                if (keycode == Keys.R) {   // re-scan (models the other agents produced since show/last R)
                    gallery.build();
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
