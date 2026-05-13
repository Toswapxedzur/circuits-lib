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
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import com.minecart.client.logic.ClientLevel;
import com.minecart.client.network.ClientConnection;
import com.minecart.display.DisplayApp;
import com.minecart.display.editor.Editor;
import com.minecart.display.editor.EditorTool;
import com.minecart.display.editor.PaletteEntries;
import com.minecart.display.input.CameraController;
import com.minecart.display.render.Textures;
import com.minecart.display.render.UiIcons;
import com.minecart.display.render.WorldStage;
import com.minecart.foundation.World;
import com.minecart.protocol.payload.client.CreateWorldPayload;
import com.minecart.protocol.payload.client.DeleteWorldPayload;
import com.minecart.protocol.payload.client.RenameWorldPayload;
import com.minecart.server.integrated.IntegratedServer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

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
 *   <li>{@link #uiStage} — fixed top bar (selected-world label + hamburger + Settings) + bottom palette
 *       (search + scrollable element tiles) + an overlay world dropdown.</li>
 * </ul>
 * The hamburger ("≡") button toggles a dropdown listing every world the server knows about, with per-row
 * Modify (rename) and Trash (delete) controls and a "+ Create world" footer. The user must select a world
 * here before they can place anything; trying to place without a selection flashes a warning in the status
 * label.
 */
public class GameScreen extends ScreenAdapter {

    private final DisplayApp app;
    private final Skin skin;
    private final String saveName;
    private final ClientLevel clientLevel;
    private final ClientConnection connection;
    /** {@code null} in multiplayer (no integrated server, no on-disk save). */
    private final IntegratedServer integrated;

    private final Textures textures;
    private final UiIcons uiIcons;
    private final WorldStage worldStage;
    private final Stage uiStage;
    private final Editor editor;
    private final CameraController cameraController;

    private final Label statusLabel;
    private final Label toolLabel;
    private final Label selectedWorldLabel;
    private final Table paletteTilesTable;
    private TextField searchField;

    /** Currently-selected target world for placement; {@code null} until the user picks one. */
    private UUID selectedWorldId;

    /** Right-edge dropdown panel: header + scrollable world list + create-world footer. */
    private final Table worldDropdown;
    private final Table worldListBody;
    private boolean worldDropdownOpen;

    // Persistent dropdown chrome -- built once in the constructor and re-added on rebuild instead of
    // reallocating per frame. Recreating these every frame was killing click detection: a touchDown on a
    // button would land on an actor that the next render-loop rebuild destroyed before the touchUp arrived,
    // so ClickListener never fired.
    private final Label dropdownHeader;
    private final Label dropdownHint;
    private final ScrollPane worldListScroll;
    private final TextButton createWorldBtn;
    private final TextButton closeDropdownBtn;
    private final ImageButton.ImageButtonStyle trashStyle;
    private final TextureRegionDrawable trashIconDrawable;

    /**
     * Snapshot of {@link com.minecart.client.logic.ClientLevel#worldsRevision()} at the last rebuild. The
     * render loop only rebuilds the dropdown when this falls behind the live counter, so we avoid the old
     * "60 rebuilds per second while open" pattern and -- crucially -- world-row actors live long enough for
     * touchDown/touchUp pairs to find the same {@link ClickListener}. Starts at {@code -1} so the first
     * open-triggered rebuild always runs.
     */
    private int lastSeenWorldsRevision = -1;

    /** Hides "Save & Quit" while the snapshot is flushing so the user can't double-click. */
    private boolean shuttingDown;
    /** The currently-open settings dialog, or {@code null}. */
    private Dialog settingsDialog;

    public GameScreen(DisplayApp app,
                      String saveName,
                      ClientLevel clientLevel,
                      ClientConnection connection,
                      IntegratedServer integrated) {
        this.app = app;
        this.skin = app.getSkin();
        this.saveName = saveName;
        this.clientLevel = clientLevel;
        this.connection = connection;
        this.integrated = integrated;
        this.textures = new Textures();
        this.uiIcons = new UiIcons();
        this.worldStage = new WorldStage(clientLevel, textures);
        this.uiStage = new Stage(new ScreenViewport());
        this.cameraController = new CameraController(worldStage);
        this.statusLabel = new Label("", skin, "muted");
        this.toolLabel = new Label("Tool: none", skin, "muted");
        this.selectedWorldLabel = new Label("World: (none -- pick one)", skin, "muted");
        this.paletteTilesTable = new Table();
        this.worldListBody = new Table();
        this.worldDropdown = new Table();

        this.dropdownHeader = new Label("Worlds", skin);
        this.dropdownHeader.setFontScale(1.1f);
        this.dropdownHint = new Label("Click a name to select", skin, "muted");

        this.worldListScroll = new ScrollPane(worldListBody, skin);
        this.worldListScroll.setFadeScrollBars(false);
        this.worldListScroll.setScrollingDisabled(true, false);

        this.createWorldBtn = new TextButton("+ Create world", skin);
        this.createWorldBtn.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                requestCreateWorld();
            }
        });

        this.closeDropdownBtn = new TextButton("Close", skin);
        this.closeDropdownBtn.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                closeWorldDropdown();
            }
        });

        this.trashIconDrawable = new TextureRegionDrawable(new TextureRegion(uiIcons.trash()));
        this.trashStyle = new ImageButton.ImageButtonStyle();
        this.trashStyle.up = skin.getDrawable("d_button");
        this.trashStyle.over = skin.getDrawable("d_button_h");
        this.trashStyle.down = skin.getDrawable("d_button_d");
        this.trashStyle.imageUp = trashIconDrawable;

        this.editor = new Editor(
                clientLevel,
                connection,
                worldStage,
                () -> selectedWorldId,
                () -> flashStatus("Select or create a world before placing anything."));
        buildUi();
    }

    private boolean isSingleplayer() {
        return integrated != null;
    }

    private void buildUi() {
        Label title = new Label("Save: " + saveName, skin);
        title.setFontScale(1.2f);

        String modeText = isSingleplayer()
                ? "(integrated server)"
                : "(remote server)";
        Label mode = new Label(modeText, skin, "muted");

        TextButton worldsToggle = new TextButton("Worlds", skin);
        worldsToggle.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                toggleWorldDropdown();
            }
        });

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
        topLeft.add(mode).left().row();
        topLeft.add(selectedWorldLabel).left();
        topBar.add(topLeft).expandX().left();
        topBar.add(toolLabel).right().padRight(12f);
        topBar.add(worldsToggle).width(90f).height(32f).padRight(6f).right();
        topBar.add(settings).width(100f).height(32f).right();
        uiStage.addActor(topBar);

        // World dropdown: anchored to the right edge of the screen, descends below the top bar. Hidden
        // until the "Worlds" toggle opens it. We position the panel absolutely (instead of via a
        // setFillParent layout) because it overlays the world canvas and must sit above the palette.
        worldDropdown.setBackground(skin.getDrawable("d_panel"));
        worldDropdown.pad(8f);
        worldDropdown.top();
        worldDropdown.setVisible(false);
        uiStage.addActor(worldDropdown);

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
        refreshSelectedWorldLabel();

        // Centre the camera so (0,0) is mid-screen at start.
        worldStage.getCamera().position.set(0f, 0f, 0f);
        worldStage.getCamera().update();
    }

    // --- World dropdown ---

    private void toggleWorldDropdown() {
        worldDropdownOpen = !worldDropdownOpen;
        worldDropdown.setVisible(worldDropdownOpen);
        if (worldDropdownOpen) {
            rebuildWorldDropdown();
        }
    }

    private void closeWorldDropdown() {
        if (!worldDropdownOpen) return;
        worldDropdownOpen = false;
        worldDropdown.setVisible(false);
    }

    /**
     * Rebuilds the world list rows + the create footer. Called on dropdown open and whenever
     * {@link com.minecart.client.logic.ClientLevel#worldsRevision()} advances past
     * {@link #lastSeenWorldsRevision}. The chrome actors (header, hint, scroll pane, create/close buttons)
     * are persistent fields -- only the per-world rows inside {@link #worldListBody} get reallocated, and
     * even those only when the world set actually changed. {@link #lastSeenWorldsRevision} is updated as the
     * last step so the render-loop gate naturally stays in sync.
     */
    private void rebuildWorldDropdown() {
        worldDropdown.clearChildren();

        worldDropdown.add(dropdownHeader).left().padBottom(2f).row();
        worldDropdown.add(dropdownHint).left().padBottom(8f).row();

        worldListBody.clearChildren();
        List<World> worlds = new ArrayList<>(clientLevel.getWorlds());
        if (worlds.isEmpty()) {
            Label empty = new Label("(no worlds yet -- create one below)", skin, "muted");
            worldListBody.add(empty).left().padBottom(6f).colspan(3).row();
        } else {
            for (World w : worlds) {
                addWorldRow(w);
            }
        }
        // Cap the list height so very large saves still leave room for the "create" footer.
        float maxListH = Math.max(80f, uiStage.getHeight() - 220f);
        worldDropdown.add(worldListScroll).fillX().expandX().minWidth(280f).maxHeight(maxListH).row();

        worldDropdown.add(createWorldBtn).fillX().padTop(8f).height(32f).row();
        worldDropdown.add(closeDropdownBtn).fillX().padTop(4f).height(28f).row();

        // Anchor to the top-right corner of the UI stage, below the top bar (~80 px).
        worldDropdown.pack();
        worldDropdown.setPosition(
                uiStage.getWidth() - worldDropdown.getWidth() - 12f,
                uiStage.getHeight() - 80f,
                Align.topLeft);

        lastSeenWorldsRevision = clientLevel.worldsRevision();
    }

    private void addWorldRow(World w) {
        boolean selected = w.getId().equals(selectedWorldId);
        String name = (w.getName() != null && !w.getName().isEmpty())
                ? w.getName()
                : w.getId().toString().substring(0, 8);
        // The select button shows the world name (and an ASCII active marker so users can tell at a
        // glance which world the editor is currently targeting). Clicking it makes that world the
        // placement target.
        TextButton selectBtn = new TextButton((selected ? "[active] " : "         ") + name, skin);
        selectBtn.getLabel().setAlignment(Align.left);
        selectBtn.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                selectWorld(w.getId());
            }
        });
        // Modify: rename popup.
        TextButton modifyBtn = new TextButton("Rename", skin);
        modifyBtn.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                openModifyWorldDialog(w);
            }
        });
        // Trash: image button so it doesn't depend on emoji glyphs the default skin font can't draw. The
        // style + drawable are shared across all rows (built once in the constructor).
        ImageButton trashBtn = new ImageButton(trashStyle);
        trashBtn.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                openConfirmDeleteWorldDialog(w);
            }
        });
        worldListBody.add(selectBtn).left().fillX().expandX().minWidth(180f).height(30f).padBottom(4f);
        worldListBody.add(modifyBtn).padLeft(6f).padBottom(4f).width(80f).height(30f);
        worldListBody.add(trashBtn).padLeft(6f).padBottom(4f).width(36f).height(30f).row();
    }

    private void selectWorld(UUID id) {
        selectedWorldId = id;
        World w = clientLevel.findWorld(id);
        flashStatus("Selected world: " + (w != null && w.getName() != null ? w.getName() : id.toString().substring(0, 8)));
        refreshSelectedWorldLabel();
        if (worldDropdownOpen) rebuildWorldDropdown();
    }

    private void requestCreateWorld() {
        String name = "World " + (clientLevel.getWorlds().size() + 1);
        connection.send(new CreateWorldPayload(name));
        flashStatus("Creating " + name + "...");
        // Server replies asynchronously; close + clear the panel so the user sees the new entry next open.
        closeWorldDropdown();
    }

    /** Two-step delete so a stray click doesn't nuke a world. */
    private void openConfirmDeleteWorldDialog(World w) {
        String label = (w.getName() != null && !w.getName().isEmpty()) ? w.getName() : w.getId().toString().substring(0, 8);
        Dialog dialog = new Dialog("Delete world?", skin);
        Table content = dialog.getContentTable();
        content.pad(10f);
        content.add(new Label("Permanently delete \"" + label + "\"?", skin)).left().row();
        content.add(new Label("This cannot be undone.", skin, "muted")).left().padTop(4f).row();

        Table buttons = dialog.getButtonTable();
        buttons.pad(8f);
        TextButton confirm = new TextButton("Delete", skin);
        confirm.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                requestDeleteWorld(w.getId());
                dialog.hide();
            }
        });
        TextButton cancel = new TextButton("Cancel", skin);
        cancel.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { dialog.hide(); }
        });
        buttons.add(confirm).width(110f).height(36f).padRight(8f);
        buttons.add(cancel).width(110f).height(36f);
        dialog.show(uiStage);
    }

    private void requestDeleteWorld(UUID id) {
        connection.send(new DeleteWorldPayload(id));
        if (id.equals(selectedWorldId)) {
            selectedWorldId = null;
            refreshSelectedWorldLabel();
        }
        flashStatus("Deleting world...");
        if (worldDropdownOpen) rebuildWorldDropdown();
    }

    private void openModifyWorldDialog(World w) {
        Dialog dialog = new Dialog("Modify world", skin) {
            @Override protected void result(Object obj) {
                // No-op; explicit close below.
            }
        };
        Table content = dialog.getContentTable();
        content.pad(10f);
        content.add(new Label("Rename:", skin)).left().padRight(8f);
        TextField nameField = new TextField(w.getName() != null ? w.getName() : "", skin);
        nameField.setMessageText("World name");
        content.add(nameField).width(220f).row();

        Table buttons = dialog.getButtonTable();
        buttons.pad(8f);
        TextButton apply = new TextButton("Apply", skin);
        apply.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                String newName = nameField.getText().trim();
                if (!newName.isEmpty() && !newName.equals(w.getName())) {
                    connection.send(new RenameWorldPayload(w.getId(), newName));
                    flashStatus("Renaming...");
                }
                dialog.hide();
            }
        });
        TextButton cancel = new TextButton("Cancel", skin);
        cancel.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { dialog.hide(); }
        });
        buttons.add(apply).width(100f).height(36f).padRight(8f);
        buttons.add(cancel).width(100f).height(36f);
        dialog.show(uiStage);
    }

    private void refreshSelectedWorldLabel() {
        if (selectedWorldId == null) {
            selectedWorldLabel.setText("World: (none — pick one)");
            return;
        }
        World w = clientLevel.findWorld(selectedWorldId);
        if (w == null) {
            selectedWorldLabel.setText("World: (gone)");
            return;
        }
        String n = w.getName();
        selectedWorldLabel.setText("World: " + (n != null && !n.isEmpty() ? n : selectedWorldId.toString().substring(0, 8)));
    }

    // --- Palette ---

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
        if (selectedWorldId == null || clientLevel.findWorld(selectedWorldId) == null) {
            flashStatus("Select or create a world before picking a tool.");
            return;
        }
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
            toolLabel.setText(String.format(Locale.ROOT, "Tool: place %s @%.0f\u00B0",
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

    /**
     * Builds and shows the in-world settings dialog. The previous version cleared {@link #settingsDialog}
     * only via {@link Dialog#result(Object)}, which only fires for buttons added with
     * {@link Dialog#button(String, Object)}. Buttons added directly to {@link Dialog#getButtonTable()}
     * called {@code dialog.hide()} without firing {@code result()}, so the field stayed non-null and the
     * next {@link #openSettingsDialog()} call hit the early-return. Now we override {@link Dialog#hide()}
     * (both no-arg and Action-arg overloads) to also clear the field, plus an {@code addListener} on
     * removal as a belt-and-braces fallback.
     */
    private void openSettingsDialog() {
        if (settingsDialog != null && settingsDialog.getStage() != null) return;
        settingsDialog = null; // stale reference if the previous one was already detached
        Dialog dialog = new Dialog("Settings", skin) {
            @Override public void hide() {
                settingsDialog = null;
                super.hide();
            }
            @Override public void hide(com.badlogic.gdx.scenes.scene2d.Action action) {
                settingsDialog = null;
                super.hide(action);
            }
            @Override protected void result(Object obj) {
                settingsDialog = null;
            }
        };
        Table content = dialog.getContentTable();
        content.pad(10f);
        content.add(new Label("Save: " + saveName, skin)).left().row();
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
        // UI first so palette / dropdown / settings clicks win.
        mux.addProcessor(uiStage);
        // Camera (right/middle drag + scroll) before editor so editor only sees left clicks.
        mux.addProcessor(cameraController);
        // Editor handles palette-driven left clicks + R + Esc.
        mux.addProcessor(editor);
        // Falls through to settings dialog toggle if Esc not consumed by Editor.
        mux.addProcessor(new InputAdapter() {
            @Override public boolean keyDown(int keycode) {
                if (keycode == Input.Keys.ESCAPE) {
                    if (worldDropdownOpen) {
                        closeWorldDropdown();
                    } else if (settingsDialog != null && settingsDialog.getStage() != null) {
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
        refreshSelectedWorldLabel();

        if (worldDropdownOpen && clientLevel.worldsRevision() != lastSeenWorldsRevision) {
            // Rebuild only when the client mirror actually changed (server INSERT/REMOVE/RENAME applied).
            // The old per-frame rebuild churned all row actors at 60fps, which prevented Scene2D's
            // ClickListener from ever pairing a touchDown with the matching touchUp -- clicks on rows
            // were silently dropped. Now the revision counter on ClientLevel acts as the dirty flag.
            rebuildWorldDropdown();
        }

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
        uiIcons.dispose();
    }
}
