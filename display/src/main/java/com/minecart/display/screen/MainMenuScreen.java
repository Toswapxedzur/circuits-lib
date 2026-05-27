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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MainMenuScreen extends ScreenAdapter {

    private static final Logger log = LoggerFactory.getLogger(MainMenuScreen.class);

    private final DisplayApp app;
    private final Skin skin;
    private final Stage stage;

    public MainMenuScreen(DisplayApp app) {
        this.app = app;
        this.skin = app.getSkin();
        this.stage = new Stage(new ScreenViewport());
        buildUi();
    }

    private void buildUi() {
        Label title = new Label("CircuitsLib", skin);
        title.setFontScale(1.6f);

        TextButton singleplayer = new TextButton("Singleplayer", skin);
        TextButton multiplayer  = new TextButton("Multiplayer",  skin);
        TextButton settings     = new TextButton("Settings",     skin);
        TextButton quit         = new TextButton("Quit",          skin);

        singleplayer.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                app.setScreen(new WorldListScreen(app));
            }
        });
        multiplayer.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                app.setScreen(new MultiplayerScreen(app));
            }
        });
        settings.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                log.info("Settings (not yet implemented)");
            }
        });
        // Closes the LibGDX application; on desktop this terminates the JVM via the Lwjgl3Application
        // shutdown hook.
        quit.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                Gdx.app.exit();
            }
        });

        Table root = new Table();
        root.setFillParent(true);
        root.add(title).padBottom(28f).row();
        root.add(singleplayer).width(260f).height(56f).padBottom(12f).row();
        root.add(multiplayer ).width(260f).height(56f).padBottom(12f).row();
        root.add(settings    ).width(260f).height(56f).padBottom(12f).row();
        root.add(quit        ).width(260f).height(56f).row();
        stage.addActor(root);
    }

    @Override public void show() {
        Gdx.input.setInputProcessor(stage);
    }

    @Override public void render(float dt) {
        Gdx.gl.glClearColor(0.08f, 0.09f, 0.11f, 1f);
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
