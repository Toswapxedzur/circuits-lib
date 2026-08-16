package com.minecart.display.screen;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input.Buttons;
import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.math.Vector3;
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
import com.minecart.display.input.FreeCameraController;
import com.minecart.display.render.snap.SnapEditor;
import com.minecart.display.render.snap.SnapRenderer;
import com.minecart.display.render.snap.SnapScene;
import com.minecart.display.render.snap.SnapSceneGeometry;
import com.minecart.foundation.World;
import com.minecart.logic.ServerWorld;
import com.minecart.server.integrated.IntegratedServer;
import com.minecart.snap.SnapBoard;
import com.minecart.snap.SnapPlacement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * The 3D snap-circuit editor ({@link com.minecart.foundation.GameMode#SNAP_3D}). Reached from
 * {@link WorldListScreen} for any save created in snap mode, in place of the 2D {@link GameScreen}.
 *
 * <p><b>Phase 3b status:</b> a Minecraft/Lego-style build loop. A bottom hotbar chooses the item
 * (wire / resistor / battery / eraser); a translucent ghost previews the placement — green where valid,
 * red where not — snapping to the ground lattice or stacking on the part under the cursor; <kbd>R</kbd>
 * rotates it; left-click places (or, with the eraser, removes) the part. Edits are applied on the server's
 * tick thread via its action queue, then the scene refreshes when the board's revision advances. The
 * player flies freely (W/A/S/D + Space/Ctrl, right-drag to look, scroll to dolly).
 */
public final class SnapScreen extends ScreenAdapter {

    private static final Logger log = LoggerFactory.getLogger(SnapScreen.class);

    private final DisplayApp app;
    private final String worldName;
    private final ClientLevel level;
    private final ClientConnection connection;
    private final IntegratedServer integrated;

    private final Stage uiStage;
    private final ServerWorld serverWorld;
    private final SnapBoard board;

    private PerspectiveCamera camera;
    private FreeCameraController flyCam;
    private SnapRenderer renderer;
    private SnapScene scene;
    private SnapEditor editor;
    private InputMultiplexer input;

    private Label statusLabel;
    private TextButton[] hotbarButtons;
    private int lastRevision = Integer.MIN_VALUE;

    private boolean shuttingDown;
    private boolean disposed;

    public SnapScreen(DisplayApp app, String worldName, ClientLevel level,
                      ClientConnection connection, IntegratedServer integrated) {
        this.app = app;
        this.worldName = worldName;
        this.level = level;
        this.connection = connection;
        this.integrated = integrated;
        this.uiStage = new Stage(new ScreenViewport());
        this.serverWorld = snapWorld(integrated);
        this.board = serverWorld != null ? serverWorld.getSnapBoard() : null;
        buildUi();
        if (board != null) {
            buildScene();
        }
    }

    /** In singleplayer the authoritative board lives on the integrated server's world. */
    private static ServerWorld snapWorld(IntegratedServer integrated) {
        if (integrated == null) {
            return null;
        }
        for (World w : integrated.level().getWorlds()) {
            if (w instanceof ServerWorld sw && sw.getSnapBoard() != null) {
                return sw;
            }
        }
        return null;
    }

    private void buildScene() {
        float cell = SnapSceneGeometry.CELL;
        float centerX = board.width() * cell / 2f;
        float centerZ = board.height() * cell / 2f;
        float span = Math.max(board.width(), board.height()) * cell + cell;

        camera = new PerspectiveCamera(60f, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        camera.near = 0.5f;
        camera.far = span * 12f;
        Vector3 start = new Vector3(centerX, span * 0.85f, centerZ + span * 1.15f);
        flyCam = new FreeCameraController(camera, start, new Vector3(centerX, 0f, centerZ), span);

        renderer = new SnapRenderer();
        editor = new SnapEditor(board);
        refreshScene();
    }

    // --- UI -------------------------------------------------------------------------------------

    private void buildUi() {
        Skin skin = app.getSkin();

        Label title = new Label("3D Snap: " + worldName, skin);
        statusLabel = new Label("", skin, "muted");

        TextButton saveBack = new TextButton("Save & Back", skin);
        TextButton back = new TextButton("Back", skin);
        saveBack.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { saveAndBack(); }
        });
        back.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { leaveWithoutSaving(); }
        });

        Table topBar = new Table();
        topBar.setFillParent(true);
        topBar.top().pad(12f);
        topBar.add(title).left().expandX();
        topBar.add(saveBack).width(150f).height(40f).padRight(8f);
        topBar.add(back).width(110f).height(40f).row();
        topBar.add(statusLabel).left().colspan(3).padTop(6f);
        uiStage.addActor(topBar);

        if (board != null) {
            buildHotbar(skin);
        } else {
            statusLabel.setText("No board to display for this session.");
        }
    }

    private void buildHotbar(Skin skin) {
        SnapEditor.Tool[] tools = SnapEditor.Tool.values();
        hotbarButtons = new TextButton[tools.length];

        Table hotbar = new Table();
        hotbar.setFillParent(true);
        hotbar.bottom().pad(16f);
        for (int i = 0; i < tools.length; i++) {
            SnapEditor.Tool tool = tools[i];
            TextButton button = new TextButton((i + 1) + "  " + tool.label(), skin);
            button.addListener(new ClickListener() {
                @Override public void clicked(InputEvent e, float x, float y) {
                    if (editor != null) {
                        editor.select(tool);
                        refreshHotbar();
                    }
                }
            });
            hotbarButtons[i] = button;
            hotbar.add(button).width(120f).height(44f).padLeft(6f).padRight(6f);
        }
        uiStage.addActor(hotbar);
    }

    private void refreshHotbar() {
        if (hotbarButtons == null || editor == null) {
            return;
        }
        SnapEditor.Tool[] tools = SnapEditor.Tool.values();
        for (int i = 0; i < hotbarButtons.length; i++) {
            hotbarButtons[i].setColor(tools[i] == editor.tool() ? Color.LIME : Color.WHITE);
        }
    }

    private void updateStatus() {
        if (editor == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Item: ").append(editor.tool().label());
        if (!editor.tool().isDelete()) {
            sb.append("  Facing: ").append(editor.facing());
        }
        sb.append("    |    1-4 select • R rotate • LMB ");
        sb.append(editor.tool().isDelete() ? "erase" : "place");
        sb.append(" • WASD move • right-drag look");
        if (editor.hovered() != null) {
            sb.append("    |    aiming: ").append(editor.hovered().placement().type().id());
        }
        statusLabel.setText(sb.toString());
    }

    // --- edit actions (server-authoritative) ----------------------------------------------------

    /** Left-click: place the ghost, or with the eraser remove the part under the cursor. */
    private void primaryAction() {
        if (editor == null || board == null || integrated == null) {
            return;
        }
        if (editor.tool().isDelete()) {
            SnapScene.Pickable target = editor.hovered();
            if (target != null) {
                submitRemove(target.placement());
            }
        } else if (editor.ghostValid()) {
            submitPlace(editor.ghost());
        }
    }

    private void submitPlace(SnapPlacement placement) {
        integrated.level().submit(() -> {
            SnapBoard b = serverWorld.getSnapBoard();
            if (b != null && b.place(placement)) {
                b.rebuild(serverWorld);
            }
        });
    }

    private void submitRemove(SnapPlacement placement) {
        integrated.level().submit(() -> {
            SnapBoard b = serverWorld.getSnapBoard();
            if (b != null && b.remove(placement.postA(), placement.postB()) != null) {
                b.rebuild(serverWorld);
            }
        });
    }

    /** Rebuilds the drawable/pickable scene from the current board and records its revision. */
    private void refreshScene() {
        // Read the revision BEFORE snapshotting. If an edit lands in the gap, the snapshot includes it
        // while lastRevision stays behind, forcing one harmless extra refresh next frame — rather than
        // recording a newer revision than the scene reflects, which would drop that edit until the next.
        int rev = board.revision();
        scene = SnapScene.of(board);
        renderer.setScene(scene);
        lastRevision = rev;
    }

    // --- screen lifecycle -----------------------------------------------------------------------

    @Override public void show() {
        input = new InputMultiplexer();
        input.addProcessor(uiStage);
        input.addProcessor(new EditInput());
        if (flyCam != null) {
            input.addProcessor(flyCam);
        }
        Gdx.input.setInputProcessor(input);
        refreshHotbar();
    }

    @Override public void render(float dt) {
        Gdx.gl.glClearColor(0.06f, 0.07f, 0.10f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        if (renderer != null && camera != null) {
            flyCam.update(dt);
            if (board.revision() != lastRevision) {
                refreshScene();
            }
            editor.update(camera, scene);
            renderer.setHighlight(editor.hovered() != null ? editor.hovered().box() : null);
            renderer.setGhost(editor.ghostBox(), editor.ghostValid());

            Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
            renderer.render(camera);
            Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
            updateStatus();
        }
        uiStage.act(dt);
        uiStage.draw();
    }

    @Override public void resize(int width, int height) {
        uiStage.getViewport().update(width, height, true);
        if (camera != null) {
            camera.viewportWidth = width;
            camera.viewportHeight = height;
            camera.update();
        }
    }

    /** Handles world clicks (place/erase) and edit hotkeys; camera keys are polled by the fly cam. */
    private final class EditInput extends InputAdapter {
        @Override public boolean touchDown(int screenX, int screenY, int pointer, int button) {
            if (button == Buttons.LEFT && editor != null) {
                primaryAction();
                return true;
            }
            return false;
        }

        @Override public boolean keyDown(int keycode) {
            if (editor == null) {
                return false;
            }
            if (keycode == Keys.R) {
                editor.rotate();
                return true;
            }
            if (keycode >= Keys.NUM_1 && keycode <= Keys.NUM_9) {
                editor.selectIndex(keycode - Keys.NUM_1);
                refreshHotbar();
                return true;
            }
            return false;
        }
    }

    // --- session lifecycle (mirrors GameScreen) --------------------------------------------------

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

    @Override public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        if (renderer != null) {
            renderer.dispose();
        }
        if (!shuttingDown) {
            shuttingDown = true;
            shutdownSessionNoSave();
        }
        uiStage.dispose();
    }
}
