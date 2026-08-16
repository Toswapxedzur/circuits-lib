package com.minecart.display.input;

import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;

/**
 * A minimal orbit camera for the 3D snap scene: the camera circles a fixed {@code target} at a given
 * {@code distance}, {@code yaw}, and {@code pitch}. Left-drag rotates (yaw/pitch), the scroll wheel zooms
 * (distance). Pitch is clamped away from the poles so the view can't flip. Call {@link #update()} after
 * changing any parameter (drag/scroll already do).
 */
public final class OrbitCameraController extends InputAdapter {

    private final PerspectiveCamera camera;
    private final Vector3 target = new Vector3();

    private float distance;
    private float yawDeg = 45f;
    private float pitchDeg = 35f;

    private float minDistance = 8f;
    private float maxDistance = 4000f;
    private float rotateSpeed = 0.35f;
    private float zoomFactor = 0.12f;

    private int lastX;
    private int lastY;

    public OrbitCameraController(PerspectiveCamera camera, Vector3 target, float distance) {
        this.camera = camera;
        this.target.set(target);
        this.distance = distance;
        update();
    }

    public void setZoomLimits(float min, float max) {
        this.minDistance = min;
        this.maxDistance = max;
        this.distance = MathUtils.clamp(distance, min, max);
        update();
    }

    /** Recomputes the camera position from the current orbit parameters. */
    public void update() {
        float pitch = pitchDeg * MathUtils.degreesToRadians;
        float yaw = yawDeg * MathUtils.degreesToRadians;
        float cosPitch = MathUtils.cos(pitch);
        float x = target.x + distance * cosPitch * MathUtils.cos(yaw);
        float y = target.y + distance * MathUtils.sin(pitch);
        float z = target.z + distance * cosPitch * MathUtils.sin(yaw);
        camera.position.set(x, y, z);
        camera.up.set(0f, 1f, 0f);
        camera.lookAt(target);
        camera.update();
    }

    @Override
    public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        lastX = screenX;
        lastY = screenY;
        return true;
    }

    @Override
    public boolean touchDragged(int screenX, int screenY, int pointer) {
        int dx = screenX - lastX;
        int dy = screenY - lastY;
        lastX = screenX;
        lastY = screenY;
        yawDeg += dx * rotateSpeed;
        pitchDeg = MathUtils.clamp(pitchDeg - dy * rotateSpeed, -85f, 85f);
        update();
        return true;
    }

    @Override
    public boolean scrolled(float amountX, float amountY) {
        distance = MathUtils.clamp(distance * (1f + amountY * zoomFactor), minDistance, maxDistance);
        update();
        return true;
    }
}
