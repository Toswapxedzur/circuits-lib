package com.minecart.display.screen;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
import com.badlogic.gdx.scenes.scene2d.ui.ImageButton;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import com.minecart.client.logic.ClientLevel;
import com.minecart.client.network.ClientConnection;
import com.minecart.display.DisplayApp;
import com.minecart.display.editor.Editor;
import com.minecart.display.editor.EditorTool;
import com.minecart.display.editor.PaletteEntries;
import com.minecart.display.input.CameraController;
import com.minecart.display.render.Textures;
import com.minecart.display.render.WorldStage;
import com.minecart.server.integrated.IntegratedServer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
 * UI is split across two Scene2D stages:
 * <ul>
 *   <li>{@link #worldStage} — pannable/zoomable {@link com.badlogic.gdx.graphics.OrthographicCamera}; renders the live circuit mirror.</li>
 *   <li>{@link #uiStage} — fixed top bar (title + Settings) + bottom palette (search + scrollable element tiles).</li>
 * </ul>
 * Input is routed UI → camera → editor so the palette wins clicks, then pan/zoom claims the right/middle drag, then
 * the editor handles left clicks on the canvas.
 */
public class GameScreen extends ScreenAdapter {

    private final DisplayApp app;
    private final Skin skin;
    private final String worldName;
    private final ClientLevel clientLevel;
    private final ClientConnection connection;
    /** {@code null} in multiplayer (no integrated server, no on-disk save). */
    private final IntegratedServer integrated;

    private final Textures textures;
    private final WorldStage worldStage;
    private final Stage uiStage;
    private final Editor editor;
    private final CameraController cameraController;

    private final Label statusLabel;
    private final Label toolLabel;
    private final Table paletteTilesTable;
    private TextField searchField;

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
        this.textures = new Textures();
        this.worldStage = new WorldStage(clientLevel, textures);
        this.uiStage = new Stage(new ScreenViewport());
        this.editor = new Editor(clientLevel, connection, worldStage);
        this.cameraController = new CameraController(worldStage);
        this.statusLabel = new Label("", skin, "muted");
        this.toolLabel = new Label("Tool: none", skin, "muted");
        this.paletteTilesTable = new Table();
        buildUi();
    }

    private boolean isSingleplayer() {
        return integrated != null;
    }

    private void buildUi() {
        Label title = new Label("World: " + worldName, skin);
        title.setFontScale(1.2f);

        String modeText = isSingleplayer()
                ? "(integrated server)"
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
        topBar.top().pad(8f);
        Table topLeft = new Table();
        topLeft.add(title).left().row();
        topLeft.add(mode).left();
        topBar.add(topLeft).expandX().left();
        topBar.add(toolLabel).right().padRight(12f);
        topBar.add(settings).width(100f).height(32f).right();
        uiStage.addActor(topBar);

        Table bottomBar = new Table();
        bottomBar.setFillParent(true);
        bottomBar.bottom();

        Table paletteRow = new Table();
        paletteRow.setBackground(skin.getDrawable("d_panel"));
        paletteRow.pad(8f);

        Label searchLabel = new Label("Search:", skin, "muted");
        searchField = new TextField("", skin);
        searchField.setMessageText("filter...");
        searchField.setTextFieldListener((field, c) -> rebuildPaletteTiles(field.getText()));

        ScrollPane scroll = new ScrollPane(paletteTilesTable, skin);
        scroll.setScrollingDisabled(false, true);
        scroll.setFadeScrollBars(false);

        paletteRow.add(searchLabel).padRight(6f);
        paletteRow.add(searchField).width(140f).padRight(8f);
        paletteRow.add(scroll).height(72f).expandX().fillX();
        paletteRow.add(statusLabel).padLeft(12f);

        bottomBar.add(paletteRow).expandX().fillX();
        uiStage.addActor(bottomBar);

        rebuildPaletteTiles("");
        refreshToolLabel();

        // Centre the camera so (0,0) is mid-screen at start.
        worldStage.getCamera().position.set(0f, 0f, 0f);
        worldStage.getCamera().update();
    }

    private void rebuildPaletteTiles(String filter) {
        paletteTilesTable.clearChildren();
        String f = filter == null ? "" : filter.toLowerCase(Locale.ROOT).trim();
        for (PaletteEntries.Entry entry : PaletteEntries.ALL) {
            if (!matches(entry, f)) continue;
            paletteTilesTable.add(buildTile(entry)).pad(2f).width(64f).height(56f);
        }
    }

    private static boolean matches(PaletteEntries.Entry entry, String f) {
        if (f.isEmpty()) return true;
        return entry.type().getTypeId().toLowerCase(Locale.ROOT).contains(f)
                || entry.displayName().toLowerCase(Locale.ROOT).contains(f);
    }

    private Table buildTile(PaletteEntries.Entry entry) {
        Table tile = new Table();
        tile.setBackground(skin.getDrawable("d_button"));
        TextureRegion region = new TextureRegion(textures.get(entry.type()));
        ImageButton.ImageButtonStyle style = new ImageButton.ImageButtonStyle();
        style.up = skin.getDrawable("d_button");
        style.over = skin.getDrawable("d_button_h");
        style.down = skin.getDrawable("d_button_d");
        style.imageUp = new TextureRegionDrawable(region);
        ImageButton img = new ImageButton(style);
        img.getImage().setColor(Color.WHITE);
        Label name = new Label(entry.displayName(), skin, "muted");
        name.setFontScale(0.85f);
        tile.add(img).width(40f).height(36f).row();
        tile.add(name).padTop(2f);
        tile.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                selectPaletteEntry(entry);
            }
        });
        img.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                selectPaletteEntry(entry);
            }
        });
        return tile;
    }

    private void selectPaletteEntry(PaletteEntries.Entry entry) {
        EditorTool tool = switch (entry.kind()) {
            case NODE -> new EditorTool.PlaceNode(entry.type());
            case EDGE -> new EditorTool.ConnectEdge(entry.type(), null);
            case COMPONENT -> new EditorTool.PlaceComponent(entry.type(), 0.0);
        };
        editor.setTool(tool);
        flashStatus("Selected " + entry.displayName());
        refreshToolLabel();
    }

    private void refreshToolLabel() {
        EditorTool t = editor.getTool();
        if (t instanceof EditorTool.Idle) {
            toolLabel.setText("Tool: none");
        } else if (t instanceof EditorTool.PlaceNode pn) {
            toolLabel.setText("Tool: place node " + pn.type().getTypeId());
        } else if (t instanceof EditorTool.PlaceComponent pc) {
            toolLabel.setText(String.format(Locale.ROOT, "Tool: place %s @%.0f°",
                    pc.type().getTypeId(), Math.toDegrees(pc.angle())));
        } else if (t instanceof EditorTool.ConnectEdge ce) {
            toolLabel.setText("Tool: connect " + ce.type().getTypeId()
                    + (ce.firstNodeId() == null ? " (pick start)" : " (pick end)"));
        }
    }

    // --- Save / quit core ---

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
                @Override public void clicked(InputEvent e, float x, float y) { saveNow(); }
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
            @Override public void clicked(InputEvent e, float x, float y) { dialog.hide(); }
        });
        buttons.add(back).width(140f).height(40f);

        settingsDialog = dialog;
        dialog.show(uiStage);
    }

    private void flashStatus(String text) {
        statusLabel.setText(text);
    }

    // --- Lifecycle ---

    @Override public void show() {
        InputMultiplexer mux = new InputMultiplexer();
        // UI first so palette / settings clicks win.
        mux.addProcessor(uiStage);
        // Camera (right/middle drag + scroll) before editor so editor only sees left clicks.
        mux.addProcessor(cameraController);
        // Editor handles palette-driven left clicks + R + Esc.
        mux.addProcessor(editor);
        // Falls through to settings dialog toggle if Esc not consumed by Editor.
        mux.addProcessor(new InputAdapter() {
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
            @Override public boolean keyUp(int keycode) {
                if (keycode == Input.Keys.R) {
                    refreshToolLabel();
                }
                return false;
            }
        });
        Gdx.input.setInputProcessor(mux);
    }

    @Override public void render(float dt) {
        Gdx.gl.glClearColor(0.05f, 0.06f, 0.08f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        clientLevel.tick();
        cameraController.update(dt);
        refreshToolLabel();

        worldStage.act(dt);
        worldStage.draw();

        uiStage.act(dt);
        uiStage.draw();
    }

    @Override public void resize(int width, int height) {
        worldStage.getViewport().update(width, height, false);
        uiStage.getViewport().update(width, height, true);
    }

    @Override public void dispose() {
        if (!shuttingDown) {
            shutdownSessionNoSave();
        }
        worldStage.dispose();
        uiStage.dispose();
        textures.dispose();
    }
}
