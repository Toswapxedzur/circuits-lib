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
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.ImageButton;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
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
import com.minecart.display.input.DragController;
import com.minecart.display.render.AxisLabelsActor;
import com.minecart.display.render.Textures;
import com.minecart.display.render.UiIcons;
import com.minecart.display.render.WorldStage;
import com.minecart.display.render.WorldViewState;
import com.minecart.foundation.World;
import com.minecart.protocol.payload.client.CreateWorldPayload;
import com.minecart.protocol.payload.client.DeleteWorldPayload;
import com.minecart.protocol.payload.client.RenameWorldPayload;
import com.minecart.server.integrated.IntegratedServer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private final DragController dragController;
    /** Centre-screen trashcan that appears only while the user is mid-drag. */
    private final Table trashOverlay;

    private final Label statusLabel;
    private final Label toolLabel;
    private final Label selectedWorldLabel;
    private final Table paletteTilesTable;
    private TextField searchField;

    /** Currently-selected target world for placement; {@code null} until the user picks one. */
    private UUID selectedWorldId;

    /**
     * Per-world snapshot of the camera pan + zoom. Indexed by world id so switching worlds restores both
     * the magnification and the scroll position the user left them at. Lives only for the editor session;
     * not persisted to disk.
     */
    private final Map<UUID, WorldViewState> worldViews = new HashMap<>();

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
        // Pass a supplier so WorldStage always sees the latest selection without having to be rebuilt when
        // the user picks a different world (or when the auto-recover logic below switches selection).
        this.worldStage = new WorldStage(clientLevel, textures, () -> selectedWorldId);
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
        // Trashcan overlay is built here (instead of inside buildUi) so the DragController constructed
        // right after has a non-null reference to query its bounds. We still defer adding chrome and
        // positioning to buildUi(), once the uiStage geometry is known.
        this.trashOverlay = new Table();
        this.dragController = new DragController(
                worldStage,
                connection,
                editor,
                () -> selectedWorldId,
                () -> {
                    if (!trashOverlay.isVisible() || trashOverlay.getStage() == null) {
                        return null;
                    }
                    return new float[]{
                            trashOverlay.getX(),
                            trashOverlay.getY(),
                            trashOverlay.getWidth(),
                            trashOverlay.getHeight()
                    };
                },
                this::setTrashOverlayVisible);
        buildUi();
    }

    /** Shown only while the {@link DragController} is mid-drag. */
    private void setTrashOverlayVisible(boolean visible) {
        if (trashOverlay.getStage() == null) {
            return;
        }
        trashOverlay.setVisible(visible);
        if (visible) {
            positionTrashOverlay();
        }
    }

    private void positionTrashOverlay() {
        trashOverlay.pack();
        // Anchor at the top-centre of the canvas, well below the top bar but above the palette so a long
        // upward drag from the bottom palette lands cleanly inside without crossing the top chrome.
        float x = (uiStage.getWidth() - trashOverlay.getWidth()) / 2f;
        float y = uiStage.getHeight() - trashOverlay.getHeight() - 100f;
        trashOverlay.setPosition(x, y);
    }

    private boolean isSingleplayer() {
        return integrated != null;
    }

    private void buildUi() {
        // Axis tick labels live on the UI stage so they render in pixel space, but read the worldStage
        // camera every frame. Added first so the rest of the chrome (top bar, palette, dropdown) draws on
        // top — the labels are background information for the canvas, not interactive UI. We don't need
        // sizing because the actor draws via absolute screen coordinates internally, not via its bounds.
        AxisLabelsActor axisLabels = new AxisLabelsActor(worldStage, skin.getFont("default-font"));
        // Only the bottom palette actually blocks visual space; the top bar is just a few small labels
        // with no opaque background, so we leave the same tight 18px inset there as on the left/right
        // edges. The bottom inset stays large (~25% above the bare palette height) so the X-tick row
        // floats above the palette panel rather than colliding with it.
        axisLabels.setInsets(/*top*/ 18f, /*bottom*/ 120f, /*left*/ 18f, /*right*/ 18f);
        uiStage.addActor(axisLabels);

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

        // Trashcan overlay: a panel with the trash icon + "Drop to delete" label. Hidden by default; the
        // DragController toggles visibility on drag start / end. We add it last so it draws over the world
        // canvas but below any modal dialogs (which are added on demand via Dialog.show).
        trashOverlay.setBackground(skin.getDrawable("d_panel"));
        trashOverlay.pad(14f);
        Image trashImg = new Image(uiIcons.trash());
        Label trashLabel = new Label("Drop to delete", skin, "muted");
        trashOverlay.add(trashImg).size(64f, 64f).row();
        trashOverlay.add(trashLabel).padTop(8f);
        trashOverlay.setVisible(false);
        uiStage.addActor(trashOverlay);

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
        // Snapshot the outgoing world's view before we overwrite the camera so we can come back to it.
        snapshotCurrentWorldView();
        selectedWorldId = id;
        applyWorldView(id);
        World w = clientLevel.findWorld(id);
        flashStatus("Selected world: " + (w != null && w.getName() != null ? w.getName() : id.toString().substring(0, 8)));
        refreshSelectedWorldLabel();
        if (worldDropdownOpen) rebuildWorldDropdown();
    }

    /**
     * Stores the camera's current pan + zoom under the previously-selected world id (if any). Called
     * before every state transition that's about to overwrite the live camera so the user can return to
     * the world they left and pick up where they were.
     */
    private void snapshotCurrentWorldView() {
        if (selectedWorldId == null) {
            return;
        }
        com.badlogic.gdx.graphics.OrthographicCamera cam = worldStage.getCamera();
        worldViews.put(selectedWorldId,
                new WorldViewState(cam.position.x, cam.position.y, cam.zoom));
    }

    /**
     * Loads the saved view for {@code id} into the camera (or installs a fresh
     * {@link WorldViewState#defaultView()} for first-time visits) and re-syncs the
     * {@link CameraController} lerp target so it doesn't animate back to a stale value.
     */
    private void applyWorldView(UUID id) {
        WorldViewState state = id != null
                ? worldViews.computeIfAbsent(id, k -> WorldViewState.defaultView())
                : WorldViewState.defaultView();
        com.badlogic.gdx.graphics.OrthographicCamera cam = worldStage.getCamera();
        cam.position.set(state.cameraX(), state.cameraY(), 0f);
        cam.zoom = state.zoom();
        cam.update();
        cameraController.syncTargetZoomToCamera();
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

    /**
     * Returns a sensible replacement world id after the currently-selected world disappears (deleted
     * locally or by another client). Picks the first remaining world from the mirror, preferring one with
     * a non-empty name so the label reads nicely. Returns {@code null} when the level has no worlds at all,
     * in which case the caller should clear the selection and let the canvas blank out.
     */
    private UUID pickFallbackWorld() {
        UUID firstUnnamed = null;
        for (World w : clientLevel.getWorlds()) {
            UUID id = w.getId();
            if (id == null) continue;
            String name = w.getName();
            if (name != null && !name.isEmpty()) {
                return id;
            }
            if (firstUnnamed == null) {
                firstUnnamed = id;
            }
        }
        return firstUnnamed;
    }

    // --- Palette ---

    private void rebuildPaletteTiles(String filter) {
        paletteTilesTable.clearChildren();
        // Cursor / "no tool" tile is always the first entry in the palette so users have a permanent
        // affordance to drop whatever placement tool they had selected. Without this, picking any
        // element from the palette traps left-click into "place" mode forever (Escape is the only
        // out) which made every left-click-drag a placement attempt and effectively hid the camera
        // pan / element drag interactions.
        paletteTilesTable.add(buildCursorTile()).pad(2f).width(64f).height(56f);
        String f = filter == null ? "" : filter.toLowerCase(Locale.ROOT).trim();
        for (PaletteEntries.Entry entry : PaletteEntries.ALL) {
            if (!matches(entry, f)) continue;
            paletteTilesTable.add(buildTile(entry)).pad(2f).width(64f).height(56f);
        }
    }

    /**
     * Builds the leftmost palette tile: an arrow-cursor icon that, when clicked, drops any active
     * placement tool back to {@link EditorTool.Idle}. Visually styled like a regular palette tile
     * (same background drawable, image + label) so it slots into the row without standing out.
     */
    private Table buildCursorTile() {
        Table tile = new Table();
        tile.setBackground(skin.getDrawable("d_button"));
        TextureRegion region = new TextureRegion(uiIcons.cursor());
        ImageButton.ImageButtonStyle style = new ImageButton.ImageButtonStyle();
        style.up = skin.getDrawable("d_button");
        style.over = skin.getDrawable("d_button_h");
        style.down = skin.getDrawable("d_button_d");
        style.imageUp = new TextureRegionDrawable(region);
        ImageButton img = new ImageButton(style);
        img.getImage().setColor(Color.WHITE);
        Label name = new Label("Cursor", skin, "muted");
        name.setFontScale(0.85f);
        tile.add(img).width(40f).height(36f).row();
        tile.add(name).padTop(2f);
        ClickListener selectIdle = new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                editor.setTool(new EditorTool.Idle());
                flashStatus("Cursor (no tool)");
                refreshToolLabel();
            }
        };
        tile.addListener(selectIdle);
        img.addListener(selectIdle);
        return tile;
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
     * Magnification range exposed via the slider in the Settings dialog. Matches
     * {@link com.minecart.display.input.CameraController}'s clamp so dragging the slider end-to-end covers
     * the same span as the scroll wheel. The free-form text input can write any positive value, including
     * outside this range.
     */
    private static final float MAG_MIN_PCT = 5f;
    private static final float MAG_MAX_PCT = 1600f;

    /**
     * Builds the "View" block that the Settings dialog inserts above its buttons. The block contains:
     * <ul>
     *   <li>A per-world slider (5%..1600%) + free-form text input + "%" label. The slider visually clamps
     *       to its bounds when the typed value exceeds them, but the actual camera honors whatever the
     *       text input committed.</li>
     *   <li>A separate global text input that, on Apply, writes the same magnification into every world's
     *       saved view state (and the live camera if a world is currently selected).</li>
     * </ul>
     * Per-world controls disable themselves when no world is selected so the user can't accidentally write
     * to nothing — the global input is always available.
     */
    private void addViewSection(Table content) {
        content.add(new Label("View", skin)).left().padTop(10f).row();

        float currentPct = clampForSlider(worldStage.getMagnificationPercent());
        Slider magSlider = new Slider(MAG_MIN_PCT, MAG_MAX_PCT, 1f, false, skin);
        magSlider.setValue(currentPct);

        TextField magField = new TextField(formatMag(worldStage.getMagnificationPercent()), skin);
        magField.setMaxLength(10);

        // suppress[0] guards against re-entrant updates between slider <-> text field (each is the other's
        // observer, so without the gate setValue + setText would ping-pong forever).
        boolean[] suppress = {false};

        magSlider.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, com.badlogic.gdx.scenes.scene2d.Actor actor) {
                if (suppress[0]) return;
                float v = magSlider.getValue();
                suppress[0] = true;
                try {
                    magField.setText(formatMag(v));
                } finally { suppress[0] = false; }
                applyPerWorldMagnification(v);
            }
        });

        magField.setTextFieldListener((field, c) -> {
            if (c == '\n' || c == '\r') {
                commitMagnificationText(field, magSlider, suppress);
            }
        });

        boolean hasWorld = selectedWorldId != null && clientLevel.findWorld(selectedWorldId) != null;
        magSlider.setDisabled(!hasWorld);
        magField.setDisabled(!hasWorld);

        content.add(new Label("This world magnification:", skin, "muted")).left().padTop(4f).row();
        Table magRow = new Table();
        magRow.add(magSlider).width(200f).padRight(8f);
        magRow.add(magField).width(80f).padRight(4f);
        magRow.add(new Label("%", skin, "muted"));
        content.add(magRow).left().row();

        // Global section: typed value applies to every world's view state (live + persisted in worldViews).
        content.add(new Label("All worlds magnification:", skin, "muted")).left().padTop(8f).row();
        TextField globalField = new TextField("", skin);
        globalField.setMaxLength(10);
        globalField.setMessageText("e.g. 200");
        TextButton applyAllBtn = new TextButton("Apply", skin);
        applyAllBtn.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                commitGlobalMagnification(globalField, magSlider, magField, suppress);
            }
        });
        Table allRow = new Table();
        allRow.add(globalField).width(120f).padRight(8f);
        allRow.add(applyAllBtn).width(80f).height(28f);
        content.add(allRow).left().row();
    }

    /**
     * Writes {@code pct} to the live camera (so the canvas zooms immediately) and updates the per-world
     * view snapshot so the change survives a world switch. The slider's range is enforced upstream — this
     * method is the one place that touches the live camera, so it also re-syncs
     * {@link CameraController#syncTargetZoomToCamera()} to keep the lerp target in step.
     */
    private void applyPerWorldMagnification(float pct) {
        if (selectedWorldId == null || clientLevel.findWorld(selectedWorldId) == null) {
            return;
        }
        worldStage.setMagnificationPercent(pct);
        cameraController.syncTargetZoomToCamera();
        // Mirror into the per-world snapshot so a world switch + back returns to this same magnification.
        com.badlogic.gdx.graphics.OrthographicCamera cam = worldStage.getCamera();
        worldViews.put(selectedWorldId,
                new WorldViewState(cam.position.x, cam.position.y, cam.zoom));
    }

    /**
     * Parses the per-world magnification text field on Enter. Rejects values that are not strictly
     * positive numbers (flashes a status message instead); valid values are applied to the camera and the
     * slider is moved to the clamped projection of the typed value so the knob stays visually meaningful.
     */
    private void commitMagnificationText(TextField field, Slider slider, boolean[] suppress) {
        Float parsed = parsePositive(field.getText());
        if (parsed == null) {
            flashStatus("Magnification must be a positive number.");
            // Snap the field back to whatever the camera currently shows so the user sees the actual state.
            suppress[0] = true;
            try { field.setText(formatMag(worldStage.getMagnificationPercent())); }
            finally { suppress[0] = false; }
            return;
        }
        applyPerWorldMagnification(parsed);
        suppress[0] = true;
        try {
            slider.setValue(clampForSlider(parsed));
            field.setText(formatMag(parsed));
        } finally { suppress[0] = false; }
    }

    /**
     * Apply button on the global row: rewrites every world's saved view to the parsed magnification (so
     * switching to a yet-unvisited world also lands on the new value) and updates the live camera if a
     * world is currently selected. The per-world slider + text field are refreshed to reflect the new
     * state.
     */
    private void commitGlobalMagnification(TextField globalField, Slider slider, TextField magField,
                                           boolean[] suppress) {
        Float parsed = parsePositive(globalField.getText());
        if (parsed == null) {
            flashStatus("Magnification must be a positive number.");
            return;
        }
        float pct = parsed;
        // Rewrite every saved view (visited worlds), then ensure every currently-known world has an entry
        // — even worlds the user hasn't visited yet should adopt the new magnification on first switch.
        for (UUID id : worldViews.keySet().toArray(new UUID[0])) {
            WorldViewState old = worldViews.get(id);
            worldViews.put(id, old.withZoom(100f / Math.max(0.0001f, pct)));
        }
        for (World w : clientLevel.getWorlds()) {
            UUID id = w.getId();
            worldViews.computeIfAbsent(id, k -> WorldViewState.defaultView());
            WorldViewState st = worldViews.get(id);
            worldViews.put(id, st.withZoom(100f / Math.max(0.0001f, pct)));
        }
        if (selectedWorldId != null && clientLevel.findWorld(selectedWorldId) != null) {
            worldStage.setMagnificationPercent(pct);
            cameraController.syncTargetZoomToCamera();
        }
        suppress[0] = true;
        try {
            slider.setValue(clampForSlider(pct));
            magField.setText(formatMag(pct));
        } finally { suppress[0] = false; }
        flashStatus("All worlds magnification set to " + formatMag(pct) + "%.");
    }

    private static float clampForSlider(float pct) {
        if (pct < MAG_MIN_PCT) return MAG_MIN_PCT;
        if (pct > MAG_MAX_PCT) return MAG_MAX_PCT;
        return pct;
    }

    /** Format like the user would write it: drop the trailing ".0" but keep meaningful decimals. */
    private static String formatMag(float pct) {
        if (Math.abs(pct - Math.round(pct)) < 1e-3f) {
            return Integer.toString(Math.round(pct));
        }
        return String.format(Locale.ROOT, "%.2f", pct).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    /** Returns the parsed positive number, or {@code null} if the input isn't a strictly positive number. */
    private static Float parsePositive(String s) {
        if (s == null) return null;
        String trimmed = s.trim();
        if (trimmed.isEmpty()) return null;
        try {
            float v = Float.parseFloat(trimmed);
            if (!Float.isFinite(v) || v <= 0f) return null;
            return v;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

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

        addViewSection(content);

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
        // Drag controller next: claims left clicks that actually hit a draggable element, and owns the
        // mouseMoved hover updates regardless of any other state.
        mux.addProcessor(dragController);
        // Editor BEFORE camera so a left click with an active placement tool is consumed for placement
        // instead of getting swallowed by the camera's left-click pan. With no placement tool the editor
        // returns false and the click falls through to the camera (left-click drag = pan empty canvas).
        mux.addProcessor(editor);
        // Camera handles left-click pan on empty canvas + middle/right drag + scroll-wheel zoom.
        mux.addProcessor(cameraController);
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

        if (clientLevel.worldsRevision() != lastSeenWorldsRevision) {
            // The world set actually changed (server INSERT/REMOVE/RENAME applied). Two responsibilities:
            //
            // 1. Auto-recover selection. If the world the editor is pinned to disappeared (e.g. another
            //    client deleted it, or the server replaced the level), pick the first remaining world or
            //    fall back to "no selection" so the canvas blanks out cleanly rather than getting stuck
            //    on a dead UUID.
            // 2. Rebuild the dropdown when it's open. The old per-frame rebuild churned all row actors
            //    at 60fps, which prevented Scene2D's ClickListener from ever pairing touchDown with
            //    touchUp -- clicks on rows were silently dropped. The revision counter is the dirty flag.
            //
            // We do BOTH whenever the revision moves (not only when the dropdown is open) so a remote
            // delete is reflected in the canvas + label immediately, regardless of dropdown state.
            if (selectedWorldId != null && clientLevel.findWorld(selectedWorldId) == null) {
                // Drop the deleted world's saved view so a recreated world with the same id starts fresh
                // instead of inheriting a stale pan/zoom from the prior life. The new id (if any) gets a
                // default view via applyWorldView below.
                worldViews.remove(selectedWorldId);
                UUID fallback = pickFallbackWorld();
                selectedWorldId = fallback;
                applyWorldView(fallback);
                refreshSelectedWorldLabel();
                if (fallback == null) {
                    flashStatus("Selected world was deleted (no other worlds available).");
                } else {
                    World fw = clientLevel.findWorld(fallback);
                    String name = fw != null && fw.getName() != null && !fw.getName().isEmpty()
                            ? fw.getName()
                            : fallback.toString().substring(0, 8);
                    flashStatus("Selected world was deleted -- switched to '" + name + "'.");
                }
            }
            lastSeenWorldsRevision = clientLevel.worldsRevision();
            if (worldDropdownOpen) {
                rebuildWorldDropdown();
            }
        }

        worldStage.act(dt);
        worldStage.draw();

        uiStage.act(dt);
        uiStage.draw();
    }

    @Override public void resize(int width, int height) {
        worldStage.getViewport().update(width, height, false);
        uiStage.getViewport().update(width, height, true);
        if (trashOverlay.isVisible()) {
            positionTrashOverlay();
        }
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
