package com.minecart.display;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;

/**
 * Empty LibGDX application that just opens a window and clears it each frame.
 * Real rendering of {@link com.minecart.client.logic.ClientWorld} state will land in later iterations.
 */
public class DisplayApp extends ApplicationAdapter {

    @Override
    public void create() {
    }

    @Override
    public void render() {
        Gdx.gl.glClearColor(0.08f, 0.09f, 0.11f, 1f);
        Gdx.gl.glClear(com.badlogic.gdx.graphics.GL20.GL_COLOR_BUFFER_BIT);
    }

    @Override
    public void dispose() {
    }
}
