package com.minecart.display.input;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;

/**
 * A Minecraft-creative-style fly camera. The mouse cursor is locked and hidden; moving the mouse turns the
 * view directly (no dragging), and the crosshair stays at screen centre. Movement is WASD + up/down, with a
 * sprint modifier. There is deliberately no scroll-to-zoom or pan — the field of view is fixed, matching
 * Minecraft, and the scroll wheel is free for the hotbar.
 *
 * <ul>
 *   <li><b>Mouse move</b> — look (yaw/pitch), only while {@link #setLookEnabled(boolean) look is enabled}
 *       (i.e. the cursor is captured).</li>
 *   <li><b>W/A/S/D</b> — move forward/left/back/right; <b>Space / Left-Ctrl</b> — up/down;
 *       <b>Left-Shift</b> — sprint.</li>
 * </ul>
 *
 * Call {@link #update(float)} every frame; it consumes the per-frame mouse delta and applies held-key
 * movement.
 */
public final class FreeCameraController {

    private final PerspectiveCamera camera;
    private float yawDeg;
    private float pitchDeg;
    private float moveSpeed;
    private float lookSensitivity = 0.15f;
    private boolean lookEnabled = true;
    private boolean skipLookDelta = true; // ignore the first mouse delta after look is (re)enabled — see update()

    private final Vector3 forward = new Vector3();
    private final Vector3 right = new Vector3();

    public FreeCameraController(PerspectiveCamera camera, Vector3 startPos, Vector3 lookAt, float moveSpeed) {
        this.camera = camera;
        this.moveSpeed = moveSpeed;
        camera.position.set(startPos);

        Vector3 dir = new Vector3(lookAt).sub(startPos).nor();
        this.pitchDeg = (float) Math.toDegrees(Math.asin(MathUtils.clamp(dir.y, -1f, 1f)));
        this.yawDeg = (float) Math.toDegrees(Math.atan2(dir.z, dir.x));
        applyOrientation();
    }

    /** When {@code false}, mouse movement is ignored (e.g. while the cursor is released for menus). */
    public void setLookEnabled(boolean lookEnabled) {
        if (lookEnabled && !this.lookEnabled) {
            skipLookDelta = true; // re-entering look: drop the accumulated jump so the view doesn't lurch
        }
        this.lookEnabled = lookEnabled;
    }

    /** Applies this frame's mouse-look delta and held-movement keys ({@code dt} seconds). */
    public void update(float dt) {
        if (lookEnabled) {
            float dx = Gdx.input.getDeltaX(), dy = Gdx.input.getDeltaY();
            if (skipLookDelta) {
                skipLookDelta = false; // the first frame after capture carries the cursor-recentre jump — ignore it
            } else {
                yawDeg += dx * lookSensitivity;
                pitchDeg = MathUtils.clamp(pitchDeg - dy * lookSensitivity, -89f, 89f);
                applyOrientation();
            }
        }

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
}
