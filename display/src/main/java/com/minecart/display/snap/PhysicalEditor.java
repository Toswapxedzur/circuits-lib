package com.minecart.display.snap;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Plane;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.minecart.display.render.engine.PhysicalBoardView;

/**
 * The client editing brain for the <b>physical free-placement</b> board: the crosshair ray hits the board plane to
 * give a continuous base position; the part is placed at that spot with the current yaw, then MAGNETICALLY SNAPPED
 * so a nearby connector mates exactly ({@link PhysicalBoardView#snap}). The result is the "true" ghost pose (the
 * view eases the drawn pose toward it). Left-click commits when the target doesn't collide.
 */
public final class PhysicalEditor {

    /** The placeable tools — every registered component, from the central {@link SnapModelBridge#CATALOG}. */
    private static final String[] TOOL_MODEL = SnapModelBridge.CATALOG.stream()
            .map(SnapModelBridge.Comp::modelId).toArray(String[]::new);
    private static final String[] TOOL_LABEL = SnapModelBridge.CATALOG.stream()
            .map(SnapModelBridge.Comp::label).toArray(String[]::new);

    private final Plane ground = new Plane(new Vector3(0f, 1f, 0f), 0f);
    private final Vector3 hit = new Vector3();
    private final Matrix4 ghost = new Matrix4();
    private int tool;
    private float yawDeg;      // extension DIRECTION (scroll / R / arrows do 90° turns)
    private int anchor;        // WHICH terminal pins to the cursor (←/→ cycle it)
    private float scrollAccum; // slows the scroll wheel: this much accumulated scroll = one 90° turn
    private static final float SCROLL_PER_TURN = 3f;
    private boolean valid;
    private boolean present;

    public int toolCount() { return TOOL_MODEL.length; }
    public int tool() { return tool; }
    public String toolLabel(int i) { return TOOL_LABEL[i]; }
    public void selectTool(int i) { if (i >= 0 && i < TOOL_MODEL.length) { tool = i; anchor = 0; } }
    public void rotate(float deltaDeg) { yawDeg = (yawDeg + deltaDeg) % 360f; }

    /** ←/→: cycle which of the part's terminals is pinned to the cursor. */
    public void cycleTerminal(int dir) { anchor += dir; }

    /** Mouse wheel: turn the extension direction in 90° steps, but SLOWLY — accumulate scroll and only turn once
     *  {@link #SCROLL_PER_TURN} has built up, so a single flick doesn't spin the part. */
    public void scrollRotate(float amountY) {
        scrollAccum += amountY;
        while (scrollAccum >= SCROLL_PER_TURN) { rotate(90f); scrollAccum -= SCROLL_PER_TURN; }
        while (scrollAccum <= -SCROLL_PER_TURN) { rotate(-90f); scrollAccum += SCROLL_PER_TURN; }
    }

    public boolean present() { return present; }
    public boolean valid() { return valid; }
    public String modelId() { return TOOL_MODEL[tool]; }
    public Matrix4 ghostTransform() { return ghost; }

    /** Recomputes the ghost transform from the crosshair ray. The part is always PINNED BY A TERMINAL to the
     *  target the crosshair resolves to — a stud on a placed part (Port Alias), else the board socket under the
     *  cursor — so the terminal follows the crosshair and R / scroll / ←→ swing the part AROUND that pinned terminal. */
    public void update(PerspectiveCamera cam, PhysicalBoardView world) {
        if (modelId().isEmpty()) { // the CURSOR tool: nothing to place, no ghost — just look / interact
            present = false;
            return;
        }
        Ray ray = cam.getPickRay(Gdx.graphics.getWidth() / 2f, Gdx.graphics.getHeight() / 2f);
        Vector3 target = world.pickTarget(ray); // a stud on a placed part the crosshair is over…
        if (target == null) {
            if (!Intersector.intersectRayPlane(ray, ground, hit)) {
                present = false;
                return;
            }
            target = new Vector3(hit.x, 0f, hit.z); // …else the board point under the cursor (snapToPort grids it)
        }
        Matrix4 snapped = world.snapToPort(modelId(), target, yawDeg, anchor);
        ghost.set(snapped);
        valid = world.canPlace(modelId(), snapped);
        present = true;
    }

    /** Commits the current ghost if valid; returns true if placed. */
    public boolean place(PhysicalBoardView world) {
        if (present && valid) {
            world.place(modelId(), ghost);
            return true;
        }
        return false;
    }
}
