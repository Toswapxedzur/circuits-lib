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
import com.minecart.client.logic.ClientLevel;
import com.minecart.client.network.ClientConnection;
import com.minecart.display.DisplayApp;
import com.minecart.server.integrated.IntegratedServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * The 3D snap-circuit editor ({@link com.minecart.foundation.GameMode#SNAP_3D}). Reached from
 * {@link WorldListScreen} for any save created in snap mode, in place of the 2D {@link GameScreen}.
 *
 * <p><b>Phase 0 status:</b> this is the mode-routing landing screen. It owns the same per-session
 * client+server objects a {@link GameScreen} does (so its lifecycle — save, stop, teardown — is already
 * correct) but does not yet render the 3D board. The perspective-camera renderer, the baseboard, part
 * placement, and grid-derived connectivity land in later phases; this screen is where they attach.
 */
public final class SnapScreen extends ScreenAdapter {

    private static final Logger log = LoggerFactory.getLogger(SnapScreen.class);

    private final DisplayApp app;
    private final String worldName;
    private final ClientLevel level;
    private final ClientConnection connection;
    private final IntegratedServer integrated;

    private final Stage stage;
    private boolean shuttingDown;
    private boolean disposed;

    public SnapScreen(DisplayApp app, String worldName, ClientLevel level,
                      ClientConnection connection, IntegratedServer integrated) {
        this.app = app;
        this.worldName = worldName;
        this.level = level;
        this.connection = connection;
        this.integrated = integrated;
        this.stage = new Stage(new ScreenViewport());
        buildUi();
    }

    private void buildUi() {
        Skin skin = app.getSkin();

        Label title = new Label("3D Snap Circuit", skin);
        title.setFontScale(1.6f);
        Label sub = new Label("World: " + worldName, skin, "muted");
        Label note = new Label(
                "Snap-circuit board mode. The 3D baseboard and pixelated parts render here (coming soon).",
                skin, "muted");

        TextButton saveBack = new TextButton("Save & Back", skin);
        TextButton back = new TextButton("Back", skin);
        saveBack.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { saveAndBack(); }
        });
        back.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { leaveWithoutSaving(); }
        });

        Table footer = new Table();
        footer.add(saveBack).width(160f).height(44f).padRight(12f);
        footer.add(back).width(120f).height(44f);

        Table root = new Table();
        root.setFillParent(true);
        root.pad(24f);
        root.add(title).padBottom(8f).row();
        root.add(sub).padBottom(20f).row();
        root.add(note).width(460f).padBottom(24f).row();
        root.add(footer);
        stage.addActor(root);
    }

    private boolean isSingleplayer() {
        return integrated != null;
    }

    private void saveAndBack() {
        if (shuttingDown) return;
        shuttingDown = true;
        if (isSingleplayer()) {
            closeConnectionQuietly();
            try {
                integrated.saveAndStop();
            } catch (IOException ex) {
                log.error("Final save failed", ex);
                integrated.stop();
            } catch (Throwable t) {
                log.error("Error during save & quit", t);
                integrated.stop();
            }
        } else {
            closeConnectionQuietly();
        }
        navigateBack();
    }

    private void leaveWithoutSaving() {
        if (shuttingDown) return;
        shuttingDown = true;
        shutdownSessionNoSave();
        navigateBack();
    }

    private void navigateBack() {
        app.setScreen(isSingleplayer() ? new WorldListScreen(app) : new MultiplayerScreen(app));
    }

    private void shutdownSessionNoSave() {
        closeConnectionQuietly();
        try {
            if (integrated != null) integrated.stop();
        } catch (Throwable t) {
            log.warn("Error stopping integrated server", t);
        }
    }

    private void closeConnectionQuietly() {
        try {
            if (connection != null) connection.close();
        } catch (Throwable t) {
            log.warn("Error closing client connection", t);
        }
    }

    @Override public void show() {
        Gdx.input.setInputProcessor(stage);
    }

    @Override public void render(float dt) {
        Gdx.gl.glClearColor(0.06f, 0.07f, 0.10f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        stage.act(dt);
        stage.draw();
    }

    @Override public void resize(int width, int height) {
        stage.getViewport().update(width, height, true);
    }

    @Override public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        // If we're being torn down by a window close (not via a Back button), the session is still live —
        // stop it so the integrated server's tick thread and Netty group don't outlive the screen.
        if (!shuttingDown) {
            shuttingDown = true;
            shutdownSessionNoSave();
        }
        stage.dispose();
    }
}
