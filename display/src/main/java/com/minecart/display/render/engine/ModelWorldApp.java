package com.minecart.display.render.engine;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.PerspectiveCamera;

/**
 * Standalone launcher for the <b>model gallery</b> (the texture displayer): lays out every committed part model
 * in a square grid. This is the same {@link ModelGalleryView} the in-game debug mode ({@code
 * ModelGalleryScreen}) uses — one implementation, two entry points. Robust to concurrent datagen (skips
 * not-yet-ready models); press <b>R</b> to re-scan. Fly with WASD + Space/Shift, drag to look, scroll for speed.
 * Run: {@code ./gradlew :display:modelworld}.
 */
public final class ModelWorldApp extends ApplicationAdapter {

    private PerspectiveCamera cam;
    private FlyController fly;
    private final ModelGalleryView gallery = new ModelGalleryView();

    @Override
    public void create() {
        cam = new PerspectiveCamera(60f, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        cam.near = 0.5f;
        cam.far = 20000f;
        cam.up.set(0f, 1f, 0f);
        gallery.build();
        float reach = gallery.gridReach();
        cam.position.set(0f, reach * 1.3f, reach * 1.3f);
        cam.lookAt(0f, 0f, 0f);
        cam.update();
        fly = new FlyController(cam, reach);
        Gdx.input.setInputProcessor(fly);
    }

    @Override
    public void render() {
        float dt = Math.min(Gdx.graphics.getDeltaTime(), 1f / 30f);
        fly.update(dt);
        if (Gdx.input.isKeyJustPressed(Input.Keys.R)) gallery.build();
        Gdx.gl.glClearColor(0.11f, 0.12f, 0.15f, 1f);
        Gdx.gl.glClear(com.badlogic.gdx.graphics.GL20.GL_COLOR_BUFFER_BIT | com.badlogic.gdx.graphics.GL20.GL_DEPTH_BUFFER_BIT);
        gallery.render(cam);
    }

    @Override
    public void resize(int width, int height) {
        cam.viewportWidth = width;
        cam.viewportHeight = height;
        cam.update();
    }

    @Override
    public void dispose() {
        gallery.dispose();
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
