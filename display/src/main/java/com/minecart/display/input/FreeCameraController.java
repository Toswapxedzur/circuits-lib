package com.minecart.display.input;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input.Buttons;
import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;

/**
 * A free-fly camera for the 3D snap editor: the player moves anywhere rather than orbiting a fixed point.
 *
 * <ul>
 *   <li><b>W/A/S/D</b> — move forward/left/back/right along the view.</li>
 *   <li><b>Space / Left-Ctrl</b> (or <b>E / Q</b>) — move up / down.</li>
 *   <li><b>Left-Shift</b> — move faster.</li>
 *   <li><b>Right-mouse drag</b> — look around (yaw/pitch). The cursor stays free for the left button
 *       to pick, so looking is on the right button.</li>
 *   <li><b>Scroll</b> — dolly along the view direction.</li>
 * </ul>
 *
 * Call {@link #update(float)} every frame to apply held-key movement; look and dolly are event-driven.
 */
public final class FreeCameraController extends InputAdapter {

    private final PerspectiveCamera camera;
    private float yawDeg;
    private float pitchDeg;
    private float moveSpeed;

    private boolean looking;
    private int lastX;
    private int lastY;

    private final Vector3 forward = new Vector3();
    private final Vector3 right = new Vector3();

    private float lookSensitivity = 0.25f;
    private float dollyStep;

    public FreeCameraController(PerspectiveCamera camera, Vector3 startPos, Vector3 lookAt, float moveSpeed) {
        this.camera = camera;
        this.moveSpeed = moveSpeed;
        this.dollyStep = moveSpeed;
        camera.position.set(startPos);

        Vector3 dir = new Vector3(lookAt).sub(startPos).nor();
        this.pitchDeg = (float) Math.toDegrees(Math.asin(MathUtils.clamp(dir.y, -1f, 1f)));
        this.yawDeg = (float) Math.toDegrees(Math.atan2(dir.z, dir.x));
        applyOrientation();
    }

    /** Applies held-movement keys for this frame's {@code dt} seconds. */
    public void update(float dt) {
        float speed = moveSpeed * dt;
        if (Gdx.input.isKeyPressed(Keys.SHIFT_LEFT)) {
            speed *= 3f;
        }
        forward.set(camera.direction).nor();
        right.set(forward).crs(Vector3.Y).nor();

        if (Gdx.input.isKeyPressed(Keys.W)) camera.position.mulAdd(forward, speed);
        if (Gdx.input.isKeyPressed(Keys.S)) camera.position.mulAdd(forward, -speed);
        if (Gdx.input.isKeyPressed(Keys.D)) camera.position.mulAdd(right, speed);
        if (Gdx.input.isKeyPressed(Keys.A)) camera.position.mulAdd(right, -speed);
        if (Gdx.input.isKeyPressed(Keys.E) || Gdx.input.isKeyPressed(Keys.SPACE)) camera.position.y += speed;
        if (Gdx.input.isKeyPressed(Keys.Q) || Gdx.input.isKeyPressed(Keys.CONTROL_LEFT)) camera.position.y -= speed;
        camera.update();
    }

    private void applyOrientation() {
        float yaw = yawDeg * MathUtils.degreesToRadians;
        float pitch = pitchDeg * MathUtils.degreesToRadians;
        float cosPitch = MathUtils.cos(pitch);
        camera.direction.set(cosPitch * MathUtils.cos(yaw), MathUtils.sin(pitch), cosPitch * MathUtils.sin(yaw)).nor();
        camera.up.set(Vector3.Y);
        camera.update();
    }

    @Override
    public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        if (button == Buttons.RIGHT) {
            looking = true;
            lastX = screenX;
            lastY = screenY;
            return true;
        }
        return false;
    }

    @Override
    public boolean touchUp(int screenX, int screenY, int pointer, int button) {
        if (button == Buttons.RIGHT) {
            looking = false;
            return true;
        }
        return false;
    }

    @Override
    public boolean touchDragged(int screenX, int screenY, int pointer) {
        if (!looking) {
            return false;
        }
        int dx = screenX - lastX;
        int dy = screenY - lastY;
        lastX = screenX;
        lastY = screenY;
        yawDeg += dx * lookSensitivity;
        pitchDeg = MathUtils.clamp(pitchDeg - dy * lookSensitivity, -89f, 89f);
        applyOrientation();
        return true;
    }

    @Override
    public boolean scrolled(float amountX, float amountY) {
        camera.position.mulAdd(camera.direction, -amountY * dollyStep);
        camera.update();
        return true;
    }
}
