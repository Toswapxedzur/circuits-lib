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
import com.minecart.snap.SnapSceneGeometry;
import com.minecart.display.render.snap.PostProcessor;
import com.minecart.display.render.snap.ToonRenderer;
import com.minecart.display.render.snap.WaterRenderer;
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
    private ToonRenderer environment;
    private WaterRenderer water;
    private PostProcessor post;
    private com.badlogic.gdx.graphics.g3d.environment.DirectionalShadowLight sun;
    private com.badlogic.gdx.graphics.g3d.Environment sharedEnv;
    private com.badlogic.gdx.graphics.g3d.ModelBatch shadowBatch;
    private Vector3 shadowCenter;
    private Vector3 sunTravel;
    private SnapScene scene;
    private SnapEditor editor;
    private InputMultiplexer input;

    private Label statusLabel;
    private TextButton[] hotbarButtons;
    private int lastRevision = Integer.MIN_VALUE;
    private boolean cursorCaught;

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
        float cell = SnapSceneGeometry.BUMP_SPACING;
        float centerX = board.width() * cell / 2f;
        float centerZ = board.height() * cell / 2f;
        float span = Math.max(board.width(), board.height()) * cell + cell;

        camera = new PerspectiveCamera(60f, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        camera.near = 1f;
        camera.far = Math.max(8000f, span * 12f); // far enough for the sky dome + distant scenery
        Vector3 start = new Vector3(centerX, span * 0.85f, centerZ + span * 1.15f);
        flyCam = new FreeCameraController(camera, start, new Vector3(centerX, 0f, centerZ), span);

        // Shared lighting + real-time shadow map (Phase R1): a warm directional sun casting into a depth
        // map, cool sky ambient, and horizon fog — sampled by DefaultShader on both the board and scenery.
        sun = new com.badlogic.gdx.graphics.g3d.environment.DirectionalShadowLight(2048, 2048, 700f, 700f, 1f, 3000f);
        sun.set(1.0f, 0.64f, 0.40f,   // warm low dawn sun
                -ToonRenderer.SUN_TO_LIGHT.x, -ToonRenderer.SUN_TO_LIGHT.y, -ToonRenderer.SUN_TO_LIGHT.z);
        sharedEnv = new com.badlogic.gdx.graphics.g3d.Environment();
        sharedEnv.set(new com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute(
                com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute.AmbientLight, 0.34f, 0.38f, 0.52f, 1f));
        sharedEnv.add(sun);
        sharedEnv.shadowMap = sun;
        shadowBatch = new com.badlogic.gdx.graphics.g3d.ModelBatch(
                new com.badlogic.gdx.graphics.g3d.utils.DepthShaderProvider());
        shadowCenter = new Vector3(centerX, 0f, centerZ);
        sunTravel = new Vector3(ToonRenderer.SUN_TO_LIGHT).scl(-1f);

        renderer = new SnapRenderer(sharedEnv);
        environment = new ToonRenderer(board, sharedEnv);
        water = new WaterRenderer(environment.waterY(), environment.pondCenterX(), environment.pondCenterZ(),
                environment.pondRadius(), ToonRenderer.SUN_TO_LIGHT, environment.sunColor());
        post = new PostProcessor();
        // Fog fades distant scenery toward the horizon colour.
        com.badlogic.gdx.graphics.Color horizon = environment.skyColor();
        sharedEnv.set(new com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute(
                com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute.Fog, horizon.r, horizon.g, horizon.b, 1f));
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
            flyCam.setLookEnabled(caught);
        }
    }

    @Override public void render(float dt) {
        if (renderer != null && camera != null) {
            flyCam.update(dt);
            if (board.revision() != lastRevision) {
                refreshScene();
            }
            editor.update(camera, scene, dt);
            renderer.setHighlight(editor.hovered() != null ? editor.hovered().box() : null);
            renderer.setGhost(editor.ghostRender(), editor.ghostValid());

            // Phase R1 shadow pass: render the casters from the sun's POV into the depth map, BEFORE the
            // screen clear (the shadow FBO must be filled before DefaultShader samples it below).
            if (sun != null && environment != null) {
                sun.begin(shadowCenter, sunTravel);
                shadowBatch.begin(sun.getCamera());
                com.badlogic.gdx.graphics.g3d.ModelInstance boardCaster = renderer.shadowCaster();
                if (boardCaster != null) {
                    shadowBatch.render(boardCaster);
                }
                for (com.badlogic.gdx.graphics.g3d.ModelInstance caster : environment.shadowCasters()) {
                    shadowBatch.render(caster);
                }
                shadowBatch.end();
                sun.end();
            }

            // Planar-reflection pass: re-render sky + scenery from a camera mirrored across the pond into the
            // water's reflection buffer, also before the screen clear (the water shader samples it below).
            if (water != null && environment != null) {
                water.update(dt);
                com.badlogic.gdx.graphics.Camera rc = water.beginReflection(camera, environment.skyColor());
                environment.renderSky(rc);
                environment.render(rc);
                water.endReflection();
            }
        }

        com.badlogic.gdx.graphics.Color sky = (environment != null) ? environment.skyColor() : null;
        float cr = sky != null ? sky.r : 0.06f, cg = sky != null ? sky.g : 0.07f, cb = sky != null ? sky.b : 0.10f;

        // The 3D scene renders into the post-processor's off-screen buffer; renderToScreen() then composites
        // it back with bloom + vignette. The UI is drawn afterward so it stays crisp (un-bloomed).
        boolean usePost = post != null && renderer != null && camera != null;
        if (usePost) {
            post.begin(cr, cg, cb);
        } else {
            Gdx.gl.glClearColor(cr, cg, cb, 1f);
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        }

        if (renderer != null && camera != null) {
            if (environment != null) {
                environment.renderSky(camera); // gradient dome + sun glow (behind everything)
                environment.render(camera);    // lit + shadowed ground / mountains / hills / trees
            }
            renderer.render(camera);           // board + ghost (shadowed via the shared environment)
            if (water != null) {
                water.render(camera);          // reflective pond (terrain shoreline depth-clips it)
            }
            updateStatus();
        }

        if (usePost) {
            post.end();
            post.renderToScreen();
        }

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

    @Override public void resize(int width, int height) {
        uiStage.getViewport().update(width, height, true);
        if (camera != null) {
            camera.viewportWidth = width;
            camera.viewportHeight = height;
            camera.update();
        }
        if (water != null) {
            water.resize(Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        }
        if (post != null) {
            post.resize(Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        }
    }

    /** Handles world clicks (place/remove), hotbar scroll/keys, and the Esc cursor toggle. */
    private final class EditInput extends InputAdapter {
        @Override public boolean touchDown(int screenX, int screenY, int pointer, int button) {
            if (editor == null) {
                return false;
            }
            if (!cursorCaught) {
                // Cursor is released for menus; a world click re-captures it (Minecraft "click to resume").
                setCursorCaught(true);
                return true;
            }
            if (button == Buttons.LEFT) {
                placeAction();
                return true;
            }
            if (button == Buttons.RIGHT) {
                removeAction();
                return true;
            }
            return false;
        }

        @Override public boolean scrolled(float amountX, float amountY) {
            if (editor == null) {
                return false;
            }
            // Small nudge to the internal heading; the placed direction snaps to the nearest viable one.
            editor.nudgeDirection(amountY > 0 ? 18f : -18f);
            return true;
        }

        @Override public boolean keyDown(int keycode) {
            if (editor == null) {
                return false;
            }
            if (keycode == Keys.ESCAPE) {
                setCursorCaught(!cursorCaught);
                return true;
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
        if (renderer != null) {
            renderer.dispose();
        }
        if (environment != null) {
            environment.dispose();
        }
        if (water != null) {
            water.dispose();
        }
        if (post != null) {
            post.dispose();
        }
        if (!shuttingDown) {
            shuttingDown = true;
            shutdownSessionNoSave();
        }
        uiStage.dispose();
    }
}
