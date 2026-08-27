package com.minecart.display.render.engine;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.IntSet;

/**
 * A free-fly inspection camera: <b>WASD</b> moves along the view (forward/back + strafe), <b>Space</b>/<b>Shift</b>
 * rise/lower along world-Y, and dragging the mouse looks around (yaw about world-up, pitch about the right axis —
 * no roll). Scroll changes fly speed. Feed key/touch events to it as the input processor and call {@link #update}
 * each frame.
 */
final class FlyController extends InputAdapter {

    private final Camera cam;
    private final IntSet down = new IntSet();
    private final Vector3 tmp = new Vector3(), move = new Vector3();
    private float speed;
    private final float lookSens = 0.15f;
    private int lastX, lastY;
    private boolean dragging;

    FlyController(Camera cam, float speed) {
        this.cam = cam;
        this.speed = speed;
    }

    @Override
    public boolean keyDown(int k) {
        down.add(k);
        return false;
    }

    @Override
    public boolean keyUp(int k) {
        down.remove(k);
        return false;
    }

    @Override
    public boolean touchDown(int x, int y, int pointer, int btn) {
        lastX = x;
        lastY = y;
        dragging = true;
        return true;
    }

    @Override
    public boolean touchUp(int x, int y, int pointer, int btn) {
        dragging = false;
        return true;
    }

    @Override
    public boolean touchDragged(int x, int y, int pointer) {
        if (!dragging) {
            return false;
        }
        float dx = (x - lastX) * lookSens, dy = (y - lastY) * lookSens;
        lastX = x;
        lastY = y;
        cam.direction.rotate(cam.up, -dx);                     // yaw about up
        tmp.set(cam.direction).crs(cam.up).nor();
        cam.direction.rotate(tmp, -dy);                        // pitch about right (up stays fixed → no roll)
        cam.update();
        return true;
    }

    @Override
    public boolean scrolled(float amountX, float amountY) {
        speed = Math.max(10f, speed * (amountY > 0 ? 0.85f : 1.18f));
        return true;
    }

    void update(float dt) {
        move.setZero();
        if (down.contains(Input.Keys.W)) move.add(cam.direction);
        if (down.contains(Input.Keys.S)) move.sub(cam.direction);
        tmp.set(cam.direction).crs(cam.up).nor();
        if (down.contains(Input.Keys.D)) move.add(tmp);
        if (down.contains(Input.Keys.A)) move.sub(tmp);
        if (down.contains(Input.Keys.SPACE)) move.y += 1f;
        if (down.contains(Input.Keys.SHIFT_LEFT) || down.contains(Input.Keys.SHIFT_RIGHT)) move.y -= 1f;
        if (!move.isZero()) {
            cam.position.add(move.nor().scl(speed * dt));
            cam.update();
        }
    }
}
