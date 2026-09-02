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
import com.badlogic.gdx.physics.bullet.Bullet;
import com.badlogic.gdx.physics.bullet.collision.btBoxShape;
import com.minecart.display.entity.EntityWorld;
import com.minecart.display.entity.WorldClock;
import com.minecart.display.input.FreeCameraController;
import com.minecart.display.render.engine.EngineBoardView;
import com.minecart.display.snap.SnapModelBridge;
import com.minecart.display.render.snap.SnapEditor;
import com.minecart.display.render.snap.SnapScene;
import com.minecart.snap.SnapSceneGeometry;
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
    private EngineBoardView boardView;   // renders the board via the instanced engine (real part models)
    private SnapScene scene;             // pickable snapshot for the editor (renderer-agnostic geometry)
    private SnapEditor editor;
    private InputMultiplexer input;

    // The world PHYSICS — its own fixed-timestep clock, SEPARATE from the electrical tick (the server-side
    // circuit solve). Static colliders come from each placed part's datagen collision box; entities (a loose
    // battery, …) will rest on/against them. Optional: if Bullet can't init, the screen still renders.
    private EntityWorld physics;
    private WorldClock physicsClock;
    private static boolean bulletReady;

    private Label statusLabel;
    private TextButton[] hotbarButtons;
    private int lastRevision = Integer.MIN_VALUE;
    private boolean cursorCaught;
    private final boolean fixedCam = "1".equals(System.getProperty("snap.fixedcam")); // dev: freeze camera for shots
    // Physical free-placement mode is now the DEFAULT 3D snap experience (continuous placement + magnetic snap +
    // typed mating + live circuit + persistence). The legacy discrete grid mode is retained as an opt-out:
    // -Dsnap.physical=off (or -Pphysical=off via runsnap).
    private final boolean physical = !"off".equals(System.getProperty("snap.physical"));
    private com.minecart.display.render.engine.PhysicalBoardView physWorld;
    private com.minecart.display.snap.PhysicalEditor physEditor;

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
        if (board != null) {
            buildScene(); // creates the editor(s) the hotbar/UI below reference
        }
        buildUi();
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
        float cell = SnapSceneGeometry.BUMP_SPACING;
        float centerX = board.width() * cell / 2f;
        float centerZ = board.height() * cell / 2f;
        float span = Math.max(board.width(), board.height()) * cell + cell;

        camera = new PerspectiveCamera(60f, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        camera.near = 1f;
        camera.far = Math.max(8000f, span * 12f); // far enough for the sky dome + distant scenery
        Vector3 start = new Vector3(centerX, span * 0.85f, centerZ + span * 1.15f);
        flyCam = new FreeCameraController(camera, start, new Vector3(centerX, 0f, centerZ), span);

        if (physical) {
            // Physical free-placement mode: continuous transforms + magnetic snap, no grid board/editor.
            physWorld = new com.minecart.display.render.engine.PhysicalBoardView();
            physWorld.setBaseBoard(board.width(), board.height(), 0f);
            physEditor = new com.minecart.display.snap.PhysicalEditor();
            int loaded = physWorld.load(physFile());
            if (loaded > 0 && serverWorld != null && integrated != null) {
                integrated.level().submit(() -> physWorld.buildCircuit(serverWorld)); // restore the live circuit
                log.info("physical: loaded {} placements from {}", loaded, physFile().path());
            }
            if ("1".equals(System.getProperty("snap.phystest"))) {
                // A live LOOP: a battery and an LED sharing BOTH terminals (coincident grid sockets) → the diode
                // conducts. Everything goes through snap() first, so it lands on the board's DISCRETE socket grid.
                float cx = centerX, cz = centerZ;
                // GRID SNAP: an arbitrary off-grid candidate (offset + odd yaw) must resolve onto sockets.
                com.badlogic.gdx.math.Matrix4 cand = new com.badlogic.gdx.math.Matrix4()
                        .setToTranslation(cx + 7f, 0f, cz + 5f).rotate(0f, 1f, 0f, 37f);
                com.badlogic.gdx.math.Matrix4 t = physWorld.snap("battery_cell", cand);
                boolean gridSnapped = physWorld.canPlace("battery_cell", t); // true ⇒ both terminals landed on sockets
                physWorld.place("battery_cell", t);
                // Connection-aware collision: an LED sharing the battery's sockets MATES (overlap allowed);
                // a wire dumped a half-cell off with no shared socket genuinely OVERLAPS + is off-grid (blocked).
                boolean mateOk = physWorld.canPlace("led", new com.badlogic.gdx.math.Matrix4(t));
                // OFF-GRID candidate (a half-cell off, terminals fall BETWEEN sockets) must be rejected.
                boolean offGridBlocked = !physWorld.canPlace("wire_2",
                        new com.badlogic.gdx.math.Matrix4().setToTranslation(cx + 6f, 0f, cz));
                // OFF-BOARD candidate (way past the grid edge) must be rejected — no free placement in empty space.
                boolean offBoardBlocked = !physWorld.canPlace("wire_2",
                        physWorld.snap("wire_2", new com.badlogic.gdx.math.Matrix4()
                                .setToTranslation(cx + 100000f, 0f, cz)));
                physWorld.place("led", new com.badlogic.gdx.math.Matrix4(t)); // battery+LED loop → the LED lights red
                if (serverWorld != null && integrated != null) {
                    integrated.level().submit(() -> physWorld.buildCircuit(serverWorld));
                }
                physWorld.save(physFile()); // persist so a subsequent (non-phystest) run loads them
                log.info("phystest: {} parts; grid-snapped={} mating-allowed={} off-grid-blocked={} off-board-blocked={}; saved {}",
                        physWorld.placements().size(), gridSnapped, mateOk, offGridBlocked, offBoardBlocked, physFile().path());
            }
            return;
        }
        boardView = new EngineBoardView(); // the board's real part models, via the instanced engine (GL20 path)
        // DEV: -Dsnap.skylight=ne|nw|se|sw overrides the skylight octant so the baked variants can be compared.
        String sky = System.getProperty("snap.skylight");
        if (sky != null) {
            float sx = sky.contains("w") ? -0.5f : 0.5f, sz = sky.contains("s") ? -0.5f : 0.5f;
            float sy = sky.contains("low") ? 0.35f : 0.7071f; // "low" = a shallow sun for long, visible shadows
            boardView.setLightDir(sx, sy, sz);
        }
        // The base board the parts sit on: tiled from committed cell + stud sprites, top surface at y=0.
        boardView.setBaseBoard(board.width(), board.height(), 0f);
        editor = new SnapEditor(board);
        // DEV: -Dsnap.testplace places a few parts on boot to screenshot-verify the place path + grid alignment.
        if ("1".equals(System.getProperty("snap.testplace")) && serverWorld != null) {
            SnapBoard b = serverWorld.getSnapBoard();
            boolean r1 = b.place(com.minecart.snap.AllSnapParts.SNAP_WIRE, 2, 2, 0, com.minecart.snap.Facing.EAST);
            boolean r2 = b.place(com.minecart.snap.AllSnapParts.SNAP_WIRE, 2, 4, 0, com.minecart.snap.Facing.NORTH);
            boolean r3 = b.place(com.minecart.snap.AllSnapParts.SNAP_RESISTOR, 4, 3, 0, com.minecart.snap.Facing.EAST);
            log.info("snap.testplace: wire(2,2,E)={} wire(2,4,N)={} resistor(4,3,E)={}", r1, r2, r3);
        }
        refreshScene();
        initPhysics();
    }

    /** Builds the world physics: a Bullet {@link EntityWorld} with a static collider per placed part (its datagen
     *  axis-aligned box), stepped on its own fixed clock. Best-effort — a physics failure never blocks rendering. */
    private void initPhysics() {
        try {
            if (!bulletReady) {
                Bullet.init();
                bulletReady = true;
            }
            physics = new EntityWorld();
            physicsClock = new WorldClock(60f, 5);
            int n = 0;
            for (EngineBoardView.PartCollider c : boardView.colliders(board.snapshot())) {
                physics.addStatic(new btBoxShape(new com.badlogic.gdx.math.Vector3(c.hx(), c.hy(), c.hz())), c.world());
                n++;
            }
            log.info("snap physics: {} static part colliders (physics clock 60Hz, separate from the electric tick)", n);
        } catch (Throwable t) {
            log.warn("snap physics init failed; continuing without it", t);
            physics = null;
        }
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
            Label crosshair = new Label("+", skin);
            crosshair.setFontScale(1.6f);
            Table center = new Table();
            center.setFillParent(true);
            center.center();
            center.add(crosshair);
            uiStage.addActor(center);
        } else {
            statusLabel.setText("No board to display for this session.");
        }
    }

    private void buildHotbar(Skin skin) {
        int n = physical ? physEditor.toolCount() : SnapEditor.Tool.values().length;
        hotbarButtons = new TextButton[n];
        Table hotbar = new Table();
        hotbar.setFillParent(true);
        hotbar.bottom().pad(16f);
        for (int i = 0; i < n; i++) {
            final int idx = i;
            String label = physical ? physEditor.toolLabel(i) : SnapEditor.Tool.values()[i].label();
            TextButton button = new TextButton((i + 1) + "  " + label, skin);
            button.addListener(new ClickListener() {
                @Override public void clicked(InputEvent e, float x, float y) {
                    if (physical) {
                        physEditor.selectTool(idx);
                    } else if (editor != null) {
                        editor.select(SnapEditor.Tool.values()[idx]);
                    }
                    refreshHotbar();
                }
            });
            hotbarButtons[i] = button;
            hotbar.add(button).width(120f).height(44f).padLeft(6f).padRight(6f);
        }
        uiStage.addActor(hotbar);
    }

    private void refreshHotbar() {
        if (hotbarButtons == null) {
            return;
        }
        int sel = physical ? physEditor.tool() : (editor == null ? -1 : editor.tool().ordinal());
        for (int i = 0; i < hotbarButtons.length; i++) {
            hotbarButtons[i].setColor(i == sel ? Color.LIME : Color.WHITE);
        }
    }

    private void updateStatus() {
        if (physical) {
            double i = physWorld.batteryCurrent();
            statusLabel.setText("PHYSICAL  |  Item: " + physEditor.toolLabel(physEditor.tool())
                    + "   |   1-" + physEditor.toolCount() + " select   scroll/R rotate   LMB place   RMB remove   Esc cursor"
                    + (physEditor.present() && !physEditor.valid() ? "    |    BLOCKED" : "")
                    + (i > 1e-4 ? String.format("    |    circuit LIVE: I = %.3f A", i) : ""));
            return;
        }
        if (editor == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Item: ").append(editor.tool().label());
        sb.append("   |   1-3 select   scroll/R direction   L/R-arrow terminal   LMB place"
                + "   RMB remove   WASD+Space/Ctrl fly   Esc cursor");
        if (editor.hovered() != null) {
            sb.append("    |    aiming: ").append(editor.hovered().placement().type().id());
        }
        statusLabel.setText(sb.toString());
    }

    // --- edit actions (server-authoritative) ----------------------------------------------------

    /** Left-click: place the ghost if the target is valid. */
    private void placeAction() {
        if (editor != null && board != null && integrated != null && editor.ghostValid()) {
            submitPlace(editor.ghost());
        }
    }

    /** Right-click: remove the part under the crosshair. */
    private void removeAction() {
        if (editor == null || board == null || integrated == null) {
            return;
        }
        SnapScene.Pickable target = editor.hovered();
        if (target != null) {
            submitRemove(target.placement());
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
            if (b != null && b.remove(placement.originPost(), placement.farPost()) != null) {
                b.rebuild(serverWorld);
            }
        });
    }

    /** Feeds this frame's placement ghost to the board view: the real component model at the ghost's true world
     *  pose (which the view eases toward). Hidden when the cursor is free or there's no valid target cell. */
    private void feedGhost(float dt) {
        if (editor != null && editor.ghost() != null && cursorCaught) {
            boardView.setGhost(true, SnapModelBridge.modelId(editor.ghost()),
                    SnapModelBridge.world(editor.ghost()), editor.ghostValid(), dt);
        } else {
            boardView.setGhost(false, null, null, false, dt);
        }
    }

    /** Rebuilds the drawable/pickable scene from the current board and records its revision. */
    private void refreshScene() {
        // Read the revision BEFORE snapshotting. If an edit lands in the gap, the snapshot includes it
        // while lastRevision stays behind, forcing one harmless extra refresh next frame — rather than
        // recording a newer revision than the scene reflects, which would drop that edit until the next.
        int rev = board.revision();
        scene = SnapScene.of(board);           // pickable snapshot for the editor
        boardView.setBoard(board.snapshot());  // the drawn parts, via the instanced engine
        lastRevision = rev;
    }

    // --- screen lifecycle -----------------------------------------------------------------------

    @Override public void show() {
        input = new InputMultiplexer();
        input.addProcessor(uiStage);
        input.addProcessor(new EditInput());
        Gdx.input.setInputProcessor(input);
        refreshHotbar();
        if (board != null) {
            setCursorCaught(true);
        }
    }

    /** Minecraft-style: capture + hide the cursor for mouse-look, or release it so menu buttons are clickable. */
    private void setCursorCaught(boolean caught) {
        cursorCaught = caught;
        Gdx.input.setCursorCatched(caught);
        if (flyCam != null) {
            flyCam.setLookEnabled(caught && !fixedCam); // fixedCam: keep the deterministic init view (dev shots)
        }
    }

    @Override public void render(float dt) {
        if (physical) {
            renderPhysical(dt);
            return;
        }
        boolean ready = boardView != null && camera != null;
        if (ready) {
            flyCam.update(dt);
            // The PHYSICS clock: fixed 60Hz steps, independent of the electrical-signal tick (the circuit solve,
            // which runs event-driven on the server's own logic tick). The two simulations never share a clock.
            if (physics != null) {
                int steps = physicsClock.advance(dt);
                for (int i = 0; i < steps; i++) {
                    physics.step(physicsClock.step());
                }
            }
            if (board.revision() != lastRevision) {
                refreshScene();
            }
            editor.update(camera, scene, dt); // computes hovered/ghost for place/remove
            feedGhost(dt);                     // hand the ghost to the board view (eased, real translucent model)
            updateStatus();
        }

        // Moderate neutral background (full component-light shading comes in a later milestone; the engine's
        // baked part art is fullbright for now). The engine sets its own depth/cull state.
        Gdx.gl.glClearColor(0.13f, 0.14f, 0.17f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);

        if (ready) {
            boardView.render(camera); // the board's real part models + the placement ghost, via the engine
        }

        // The engine leaves GL_CULL_FACE + depth test enabled; scene2d assumes them off, so its HUD quads
        // would be back-face-culled. Reset before drawing the UI.
        Gdx.gl.glDisable(GL20.GL_CULL_FACE);
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);

        uiStage.act(dt);
        uiStage.draw();

        // Dev tool: press F9 to dump the current frame to build/snap_shot.png (handy for tuning shaders).
        if (Gdx.input.isKeyJustPressed(com.badlogic.gdx.Input.Keys.F9)) {
            try {
                com.badlogic.gdx.graphics.Pixmap p = com.badlogic.gdx.graphics.Pixmap.createFromFrameBuffer(
                        0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
                com.badlogic.gdx.graphics.PixmapIO.writePNG(
                        Gdx.files.absolute("/Users/fengyue.john.zhu/Desktop/programme/java/CircuitsLib/build/snap_shot.png"), p, -1, true);
                p.dispose();
                System.out.println("[DIAG] screenshot -> build/snap_shot.png");
            } catch (Exception e) {
                System.out.println("[DIAG] screenshot failed: " + e);
            }
        }
    }

    /** Rebuilds the physical circuit on the SERVER thread (so its tick solves it), thread-safe with the render. */
    private void rebuildPhysCircuit() {
        if (serverWorld != null && integrated != null) {
            integrated.level().submit(() -> physWorld.buildCircuit(serverWorld));
        }
    }

    /** The physical free-placement render loop: free camera, ghost eased to the snapped pose, engine + HUD. */
    private void renderPhysical(float dt) {
        boolean ready = physWorld != null && camera != null;
        if (ready) {
            flyCam.update(dt);
            physEditor.update(camera, physWorld);
            physWorld.setGhost(physEditor.present() && cursorCaught, physEditor.modelId(),
                    physEditor.ghostTransform(), physEditor.valid(), dt);
            updateStatus();
        }
        Gdx.gl.glClearColor(0.13f, 0.14f, 0.17f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        if (ready) {
            physWorld.render(camera);
        }
        Gdx.gl.glDisable(GL20.GL_CULL_FACE);
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
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

    /** Handles world clicks (place/remove), hotbar scroll/keys, and the Esc cursor toggle. */
    private final class EditInput extends InputAdapter {
        @Override public boolean touchDown(int screenX, int screenY, int pointer, int button) {
            if (editor == null && !physical) {
                return false;
            }
            if (!cursorCaught) {
                // Cursor is released for menus; a world click re-captures it (Minecraft "click to resume").
                setCursorCaught(true);
                return true;
            }
            if (button == Buttons.LEFT) {
                if (physical) {
                    if (physEditor.place(physWorld)) rebuildPhysCircuit();
                } else { placeAction(); }
                return true;
            }
            if (button == Buttons.RIGHT) {
                if (physical) {
                    physEditor.update(camera, physWorld);
                    if (physWorld.removeNear(physEditor.ghostTransform().getTranslation(new Vector3()), 18f)) {
                        rebuildPhysCircuit();
                    }
                } else { removeAction(); }
                return true;
            }
            return false;
        }

        @Override public boolean scrolled(float amountX, float amountY) {
            if (physical) {
                physEditor.rotate(amountY > 0 ? 90f : -90f); // grid mode: quarter-turns (snap aligns to 90°)
                return true;
            }
            if (editor == null) {
                return false;
            }
            // Small nudge to the internal heading; the placed direction snaps to the nearest viable one.
            editor.nudgeDirection(amountY > 0 ? 18f : -18f);
            return true;
        }

        @Override public boolean keyDown(int keycode) {
            if (keycode == Keys.ESCAPE) {
                setCursorCaught(!cursorCaught);
                return true;
            }
            if (physical) {
                if (keycode == Keys.R) { physEditor.rotate(90f); return true; }
                if (keycode >= Keys.NUM_1 && keycode <= Keys.NUM_9) {
                    physEditor.selectTool(keycode - Keys.NUM_1);
                    refreshHotbar();
                    return true;
                }
                return false;
            }
            if (editor == null) {
                return false;
            }
            if (keycode == Keys.R) {
                editor.nudgeDirection(45f);
                return true;
            }
            // Left/right arrows change which terminal is anchored on the crosshair bump (no rotation).
            if (keycode == Keys.LEFT) {
                editor.cycleTerminal(-1);
                return true;
            }
            if (keycode == Keys.RIGHT) {
                editor.cycleTerminal(1);
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

    /** The per-world side-file holding this world's physical-mode placements (independent of the grid save). */
    private com.badlogic.gdx.files.FileHandle physFile() {
        String safe = (worldName == null ? "world" : worldName).replaceAll("[^A-Za-z0-9_-]", "_");
        return Gdx.files.external("circuitslib-phys/" + safe + ".json");
    }

    private void saveAndBack() {
        if (shuttingDown) return;
        shuttingDown = true;
        if (physical && physWorld != null) {
            try {
                physWorld.save(physFile());
                log.info("physical: saved {} placements to {}", physWorld.placements().size(), physFile().path());
            } catch (Throwable t) {
                log.warn("physical save failed", t);
            }
        }
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
        Gdx.input.setCursorCatched(false);
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
        Gdx.input.setCursorCatched(false);
        if (boardView != null) {
            boardView.dispose();
        }
        if (physWorld != null) {
            physWorld.dispose();
        }
        if (physics != null) {
            physics.dispose();
        }
        if (!shuttingDown) {
            shuttingDown = true;
            shutdownSessionNoSave();
        }
        uiStage.dispose();
    }
}
