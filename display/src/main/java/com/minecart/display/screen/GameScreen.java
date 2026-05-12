package com.minecart.display.screen;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
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

import java.io.IOException;

/**
 * Inside-a-world view. Owns the per-session client+server pair: in singleplayer the {@link IntegratedServer} runs the
 * authoritative simulation on its own tick thread, the {@link ClientConnection} bridges to it via Netty's in-process
 * {@code LocalChannel}, and the LibGDX render thread ticks the {@link ClientLevel} mirror once per frame. In multiplayer
 * the {@code integrated} field is {@code null} and only the client-side ticks here.
 * <p>
 * Three threads, three rules:
 * <ul>
 *   <li>{@code ServerLevel} touched only by the server-tick thread (driven by {@link IntegratedServer}).</li>
 *   <li>{@code ClientLevel} touched only by this render thread.</li>
 *   <li>Netty I/O threads only forward via {@code level.submit(...)} or {@code Gdx.app.postRunnable(...)}.</li>
 * </ul>
 * <p>
 * UI: world name + mode at top, "Settings" at top-right, "Save" / "Save & Quit" buttons at bottom (singleplayer only;
 * multiplayer shows a single "Disconnect"). {@code Esc} toggles the in-world settings dialog. Both the bottom buttons
 * and the dialog go through the same {@link #saveNow()} / {@link #saveAndQuit()} entry points so behaviour stays
 * consistent.
 */
public class GameScreen extends ScreenAdapter {

    private final DisplayApp app;
    private final Skin skin;
    private final String worldName;
    private final ClientLevel clientLevel;
    private final ClientConnection connection;
    /** {@code null} in multiplayer (no integrated server, no on-disk save). */
    private final IntegratedServer integrated;

    private final Stage stage;
    private final Label statusLabel;

    /** Hides "Save & Quit" while the snapshot is flushing so the user can't double-click. */
    private boolean shuttingDown;
    /** The currently-open settings dialog, or {@code null}. */
    private Dialog settingsDialog;

    public GameScreen(DisplayApp app,
                      String worldName,
                      ClientLevel clientLevel,
                      ClientConnection connection,
                      IntegratedServer integrated) {
        this.app = app;
        this.skin = app.getSkin();
        this.worldName = worldName;
        this.clientLevel = clientLevel;
        this.connection = connection;
        this.integrated = integrated;
        this.stage = new Stage(new ScreenViewport());
        this.statusLabel = new Label("", skin, "muted");
        buildUi();
    }

    private boolean isSingleplayer() {
        return integrated != null;
    }

    private void buildUi() {
        Label title = new Label("World: " + worldName, skin);
        title.setFontScale(1.4f);

        String modeText = isSingleplayer()
                ? "(integrated server @ " + integrated.address() + ")"
                : "(remote server)";
        Label mode = new Label(modeText, skin, "muted");

        TextButton settings = new TextButton("Settings", skin);
        settings.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                openSettingsDialog();
            }
        });

        Table topBar = new Table();
        topBar.setFillParent(true);
        topBar.top().pad(12f);
        Table topLeft = new Table();
        topLeft.add(title).left().row();
        topLeft.add(mode).left();
        topBar.add(topLeft).expandX().left();
        topBar.add(settings).width(120f).height(36f).right();
        stage.addActor(topBar);

        Label hint = new Label("(Circuit renderer will land here)", skin, "muted");
        Table center = new Table();
        center.setFillParent(true);
        center.add(hint);
        stage.addActor(center);

        Table bottomBar = new Table();
        bottomBar.setFillParent(true);
        bottomBar.bottom().pad(16f);

        if (isSingleplayer()) {
            TextButton save = new TextButton("Save", skin);
            save.addListener(new ClickListener() {
                @Override public void clicked(InputEvent e, float x, float y) {
                    saveNow();
                }
            });
            TextButton saveQuit = new TextButton("Save & Quit", skin);
            saveQuit.addListener(new ClickListener() {
                @Override public void clicked(InputEvent e, float x, float y) {
                    saveAndQuit();
                }
            });
            bottomBar.add(save).width(140f).height(44f).padRight(8f);
            bottomBar.add(saveQuit).width(160f).height(44f).padRight(8f);
            bottomBar.add(statusLabel).padLeft(8f);
        } else {
            TextButton disconnect = new TextButton("Disconnect", skin);
            disconnect.addListener(new ClickListener() {
                @Override public void clicked(InputEvent e, float x, float y) {
                    leaveWithoutSaving();
                }
            });
            bottomBar.add(disconnect).width(180f).height(44f);
        }
        stage.addActor(bottomBar);
    }

    // --- Save / quit core ---

    /**
     * Persists the world to disk (singleplayer only). Returns immediately; the actual write runs on the server-tick
     * thread between ticks. Updates {@link #statusLabel} so the user sees feedback.
     */
    private void saveNow() {
        if (!isSingleplayer() || shuttingDown) return;
        try {
            integrated.save();
            flashStatus("Saved.");
        } catch (Throwable t) {
            Gdx.app.log("GameScreen", "Save failed: " + t.getMessage(), t);
            flashStatus("Save failed.");
        }
    }

    /**
     * Singleplayer: synchronously writes a final snapshot, tears the session down, then returns to the world list.
     * Multiplayer: simply disconnects.
     */
    private void saveAndQuit() {
        if (shuttingDown) return;
        shuttingDown = true;
        flashStatus("Saving...");
        if (isSingleplayer()) {
            try {
                if (connection != null) connection.close();
            } catch (Throwable t) {
                Gdx.app.log("GameScreen", "Error closing client connection", t);
            }
            try {
                integrated.saveAndStop();
            } catch (IOException ex) {
                Gdx.app.log("GameScreen", "Final save failed: " + ex.getMessage(), ex);
                integrated.stop();
            } catch (Throwable t) {
                Gdx.app.log("GameScreen", "Error during save & quit", t);
                integrated.stop();
            }
        } else {
            try { if (connection != null) connection.close(); }
            catch (Throwable t) { Gdx.app.log("GameScreen", "Error closing client connection", t); }
        }
        navigateBack();
    }

    /** Quits without writing a final snapshot (singleplayer) or just disconnects (multiplayer). */
    private void leaveWithoutSaving() {
        if (shuttingDown) return;
        shuttingDown = true;
        shutdownSessionNoSave();
        navigateBack();
    }

    private void navigateBack() {
        app.setScreen(isSingleplayer() ? new WorldListScreen(app) : new MultiplayerScreen(app));
    }

    /** Tears down the client connection then the integrated server, but does <strong>not</strong> save. */
    private void shutdownSessionNoSave() {
        try {
            if (connection != null) connection.close();
        } catch (Throwable t) {
            Gdx.app.log("GameScreen", "Error closing client connection", t);
        }
        try {
            if (integrated != null) integrated.stop();
        } catch (Throwable t) {
            Gdx.app.log("GameScreen", "Error stopping integrated server", t);
        }
    }

    // --- Settings dialog ---

    private void openSettingsDialog() {
        if (settingsDialog != null) return;
        Dialog dialog = new Dialog("Settings", skin) {
            @Override protected void result(Object obj) {
                settingsDialog = null;
            }
        };
        Table content = dialog.getContentTable();
        content.pad(10f);
        content.add(new Label("World: " + worldName, skin)).left().row();
        if (isSingleplayer()) {
            content.add(new Label("Save dir: " + integrated.saveDir(), skin, "muted")).left().padTop(4f).row();
            content.add(new Label("Tick rate: " + integrated.level().getTickRate() + " s/tick", skin, "muted"))
                    .left().padTop(4f).row();
        } else {
            content.add(new Label("Connected to remote server", skin, "muted")).left().padTop(4f).row();
        }

        Table buttons = dialog.getButtonTable();
        buttons.pad(8f);

        if (isSingleplayer()) {
            TextButton save = new TextButton("Save", skin);
            save.addListener(new ClickListener() {
                @Override public void clicked(InputEvent e, float x, float y) {
                    saveNow();
                }
            });
            TextButton saveQuit = new TextButton("Save & Quit", skin);
            saveQuit.addListener(new ClickListener() {
                @Override public void clicked(InputEvent e, float x, float y) {
                    dialog.hide();
                    saveAndQuit();
                }
            });
            TextButton quit = new TextButton("Quit Without Saving", skin);
            quit.addListener(new ClickListener() {
                @Override public void clicked(InputEvent e, float x, float y) {
                    dialog.hide();
                    leaveWithoutSaving();
                }
            });
            buttons.add(save).width(130f).height(40f).padRight(8f);
            buttons.add(saveQuit).width(150f).height(40f).padRight(8f);
            buttons.add(quit).width(180f).height(40f).padRight(8f);
        } else {
            TextButton disconnect = new TextButton("Disconnect", skin);
            disconnect.addListener(new ClickListener() {
                @Override public void clicked(InputEvent e, float x, float y) {
                    dialog.hide();
                    leaveWithoutSaving();
                }
            });
            buttons.add(disconnect).width(160f).height(40f).padRight(8f);
        }
        TextButton back = new TextButton("Back to Game", skin);
        back.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                dialog.hide();
            }
        });
        buttons.add(back).width(140f).height(40f);

        settingsDialog = dialog;
        dialog.show(stage);
    }

    private void flashStatus(String text) {
        statusLabel.setText(text);
    }

    // --- Lifecycle ---

    @Override public void show() {
        InputMultiplexer mux = new InputMultiplexer(stage, new InputAdapter() {
            @Override public boolean keyDown(int keycode) {
                if (keycode == Input.Keys.ESCAPE) {
                    if (settingsDialog != null) {
                        settingsDialog.hide();
                    } else {
                        openSettingsDialog();
                    }
                    return true;
                }
                return false;
            }
        });
        Gdx.input.setInputProcessor(mux);
    }

    @Override public void render(float dt) {
        Gdx.gl.glClearColor(0.05f, 0.06f, 0.08f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        // Client tick on render thread (mirrors arrive via Gdx.app.postRunnable from Netty).
        clientLevel.tick();

        stage.act(dt);
        stage.draw();
    }

    @Override public void resize(int width, int height) {
        stage.getViewport().update(width, height, true);
    }

    @Override public void dispose() {
        // If the user navigated away without clicking a button, still tear the session down without saving.
        // Save&Quit / Quit-Without-Saving paths already shut down before navigating, so this is a safety net.
        if (!shuttingDown) {
            shutdownSessionNoSave();
        }
        stage.dispose();
    }
}
