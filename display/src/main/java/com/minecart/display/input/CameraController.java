package com.minecart.display.input;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.math.Vector3;
import com.minecart.display.render.WorldStage;

/**
 * Pans and zooms a {@link WorldStage}'s {@link OrthographicCamera}. Middle / right mouse drag pans;
 * scroll-wheel zooms toward the cursor with smooth interpolation toward a target zoom (so each tick of the
 * wheel doesn't snap).
 *
 * <p>Plug into the {@link com.badlogic.gdx.InputMultiplexer} <em>before</em> the world stage so we can
 * decide whether to consume mouse input. Call {@link #update(float)} every frame from the screen's
 * {@code render} method to advance the lerp.
 *
 * <p>Returns {@code false} from {@link #touchDown} when the user clicks with the left button so the editor
 * still gets first crack at clicks; right/middle drags are claimed exclusively for panning.
 */
public class CameraController extends InputAdapter {

    private static final float ZOOM_STEP = 1.15f;
    private static final float MIN_ZOOM = 0.1f;
    private static final float MAX_ZOOM = 8f;
    /** Higher = snappier zoom. 12 means we cover ~63% of the remaining distance per second. */
    private static final float ZOOM_LERP_PER_SEC = 12f;

    private final WorldStage stage;
    private final OrthographicCamera camera;

    private float targetZoom;
    private boolean panning;
    private int panButton;
    private int lastScreenX;
    private int lastScreenY;
    private boolean haveZoomAnchor;

    public CameraController(WorldStage stage) {
        this.stage = stage;
        this.camera = stage.getCamera();
        this.targetZoom = camera.zoom;
    }

    public void update(float delta) {
        if (Math.abs(camera.zoom - targetZoom) <= 1e-4f) {
            return;
        }
        float t = 1f - (float) Math.exp(-ZOOM_LERP_PER_SEC * delta);
        if (haveZoomAnchor) {
            // World point under the cursor BEFORE the zoom step.
            Vector3 before = new Vector3(screenAnchorX, screenAnchorY, 0);
            camera.unproject(before);
            camera.zoom = camera.zoom + (targetZoom - camera.zoom) * t;
            camera.update();
            // World point under the same cursor pixel AFTER the zoom step.
            Vector3 after = new Vector3(screenAnchorX, screenAnchorY, 0);
            camera.unproject(after);
            camera.position.add(before.x - after.x, before.y - after.y, 0);
            camera.update();
        } else {
            camera.zoom = camera.zoom + (targetZoom - camera.zoom) * t;
            camera.update();
        }
    }

    private float screenAnchorX;
    private float screenAnchorY;

    @Override
    public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        if (button == Input.Buttons.MIDDLE || button == Input.Buttons.RIGHT) {
            panning = true;
            panButton = button;
            lastScreenX = screenX;
            lastScreenY = screenY;
            return true;
        }
        return false;
    }

    @Override
    public boolean touchUp(int screenX, int screenY, int pointer, int button) {
        if (panning && button == panButton) {
            panning = false;
            return true;
        }
        return false;
    }

    @Override
    public boolean touchDragged(int screenX, int screenY, int pointer) {
        if (!panning) {
            return false;
        }
        int dx = screenX - lastScreenX;
        int dy = screenY - lastScreenY;
        lastScreenX = screenX;
        lastScreenY = screenY;
        float worldDx = -dx * camera.zoom * pixelSize();
        // Y is flipped between screen and world.
        float worldDy = dy * camera.zoom * pixelSize();
        camera.position.add(worldDx, worldDy, 0);
        camera.update();
        haveZoomAnchor = false;
        return true;
    }

    @Override
    public boolean scrolled(float amountX, float amountY) {
        if (Math.abs(amountY) < 1e-4f) {
            return false;
        }
        float factor = (float) Math.pow(ZOOM_STEP, amountY);
        targetZoom = clamp(targetZoom * factor, MIN_ZOOM, MAX_ZOOM);
        // Anchor the zoom to wherever the mouse currently sits so we zoom "into" the cursor.
        screenAnchorX = com.badlogic.gdx.Gdx.input.getX();
        screenAnchorY = com.badlogic.gdx.Gdx.input.getY();
        haveZoomAnchor = true;
        return true;
    }

    private float pixelSize() {
        // 1 pixel in world units (before zoom). The viewport's unitsPerPixel handles the static scale.
        com.badlogic.gdx.utils.viewport.ScreenViewport sv =
                (com.badlogic.gdx.utils.viewport.ScreenViewport) stage.getViewport();
        return sv.getUnitsPerPixel();
    }

    private static float clamp(float v, float lo, float hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }
}
