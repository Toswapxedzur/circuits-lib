package com.minecart.display.screen;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import com.minecart.display.DisplayApp;

/**
 * Placeholder for the inside-a-world view. Displays the world name and a Leave World button. The real
 * circuit renderer (camera, ShapeRenderer, click-to-place, etc.) will replace this body once the fake
 * client+server boot path is wired into {@code Join}.
 */
public class GameScreen extends ScreenAdapter {

    private final DisplayApp app;
    private final Skin skin;
    private final String worldName;
    private final Stage stage;

    public GameScreen(DisplayApp app, String worldName) {
        this.app = app;
        this.skin = app.getSkin();
        this.worldName = worldName;
        this.stage = new Stage(new ScreenViewport());
        buildUi();
    }

    private void buildUi() {
        Label title = new Label("World: " + worldName, skin);
        title.setFontScale(1.4f);

        Label hint = new Label("(Circuit renderer will land here)", skin, "muted");

        TextButton leave = new TextButton("Leave World", skin);
        leave.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                app.setScreen(new WorldListScreen(app));
            }
        });

        Table root = new Table();
        root.setFillParent(true);
        root.add(title).padBottom(8f).row();
        root.add(hint).padBottom(28f).row();
        root.add(leave).width(180f).height(48f).row();
        stage.addActor(root);
    }

    @Override public void show() {
        Gdx.input.setInputProcessor(stage);
    }

    @Override public void render(float dt) {
        Gdx.gl.glClearColor(0.05f, 0.06f, 0.08f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        stage.act(dt);
        stage.draw();
    }

    @Override public void resize(int width, int height) {
        stage.getViewport().update(width, height, true);
    }

    @Override public void dispose() {
        stage.dispose();
    }
}
