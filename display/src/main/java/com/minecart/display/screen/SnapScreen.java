package com.minecart.display.screen;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
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
import com.minecart.display.render.snap.SnapRenderer;
import com.minecart.display.render.snap.SnapScene;
import com.minecart.display.render.snap.SnapSceneGeometry;
import com.minecart.foundation.World;
import com.minecart.logic.ServerWorld;
import com.minecart.server.integrated.IntegratedServer;
import com.minecart.snap.SnapBoard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * The 3D snap-circuit editor ({@link com.minecart.foundation.GameMode#SNAP_3D}). Reached from
 * {@link WorldListScreen} for any save created in snap mode, in place of the 2D {@link GameScreen}.
 *
 * <p><b>Phase 3 status:</b> renders the board as noise-textured, lit 3D boxes; the player flies freely
 * (W/A/S/D + Space/Ctrl, right-drag to look, scroll to dolly); and a ray from the cursor picks the part
 * under it against its bounding box, highlighting it and naming it in the HUD. Server-authoritative
 * placement/removal and the part palette build on this picking + scene refresh next.
 */
public final class SnapScreen extends ScreenAdapter {

    private static final Logger log = LoggerFactory.getLogger(SnapScreen.class);

    private final DisplayApp app;
    private final String worldName;
    private final ClientLevel level;
    private final ClientConnection connection;
    private final IntegratedServer integrated;

    private final Stage uiStage;
    private final SnapBoard board;

    private PerspectiveCamera camera;
    private FreeCameraController flyCam;
    private SnapRenderer renderer;
    private SnapScene scene;
    private InputMultiplexer input;
    private Label hoverLabel;

    private final Vector3 hitPoint = new Vector3();
    private SnapScene.Pickable hovered;

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
        this.board = boardFrom(integrated);
        buildUi();
        if (board != null) {
            buildScene();
        }
    }

    /** In singleplayer the authoritative board lives on the integrated server's world. */
    private static SnapBoard boardFrom(IntegratedServer integrated) {
        if (integrated == null) {
            return null;
        }
        for (World w : integrated.level().getWorlds()) {
            if (w instanceof ServerWorld sw && sw.getSnapBoard() != null) {
                return sw.getSnapBoard();
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
        scene = SnapScene.of(board);
        renderer.setScene(scene);
    }

    private void buildUi() {
        Skin skin = app.getSkin();

        Label title = new Label("3D Snap: " + worldName, skin);
        hoverLabel = new Label("", skin, "muted");

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
        topBar.add(hoverLabel).left().colspan(3).padTop(6f);
        uiStage.addActor(topBar);
        updateHoverLabel();
    }

    private void updateHoverLabel() {
        if (board == null) {
            hoverLabel.setText("No board to display for this session.");
            return;
        }
        String base = "WASD move • Space/Ctrl up/down • right-drag look • scroll dolly";
        if (hovered != null) {
            hoverLabel.setText(base + "    |    hovering: " + hovered.placement().type().id());
        } else {
            hoverLabel.setText(base);
        }
    }

    /** Casts a ray from the cursor and keeps the nearest part whose bounding box it hits. */
    private void pick() {
        hovered = null;
        if (scene == null || camera == null) {
            return;
        }
        Ray ray = camera.getPickRay(Gdx.input.getX(), Gdx.input.getY());
        float bestDist2 = Float.MAX_VALUE;
        for (SnapScene.Pickable pk : scene.pickables()) {
            if (Intersector.intersectRayBounds(ray, pk.bounds(), hitPoint)) {
                float d2 = hitPoint.dst2(camera.position);
                if (d2 < bestDist2) {
                    bestDist2 = d2;
                    hovered = pk;
                }
            }
        }
    }

    @Override public void show() {
        input = new InputMultiplexer();
        input.addProcessor(uiStage);
        if (flyCam != null) {
            input.addProcessor(flyCam);
        }
        Gdx.input.setInputProcessor(input);
    }

    @Override public void render(float dt) {
        Gdx.gl.glClearColor(0.06f, 0.07f, 0.10f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        if (renderer != null && camera != null) {
            flyCam.update(dt);
            pick();
            renderer.setHighlight(hovered != null ? hovered.box() : null);
            Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
            renderer.render(camera);
            Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
            updateHoverLabel();
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
