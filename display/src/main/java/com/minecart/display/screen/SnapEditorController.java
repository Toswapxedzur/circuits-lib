package com.minecart.display.screen;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.minecart.display.render.engine.PhysicalBoardView;
import com.minecart.display.render.engine.PhysicalBoardView.Focus;
import com.minecart.display.input.FreeCameraController;
import com.minecart.display.snap.PhysicalEditor;

/**
 * The physical free-placement editor's per-frame brain, split out of {@link SnapScreen} (SRP). It owns the
 * interactive editing state — what the crosshair is over ({@link #physFocus}), the sub-part being dragged
 * ({@link #grabbed}), the harness's synthetic LMB-held flag, and the focus-outline segment count — and runs the
 * once-per-frame {@link #update} (grab-follows-cursor, focus pick, momentary spring-back, placement ghost) plus the
 * Minecraft-style {@link #drawOutline focus outline}. The screen keeps the shared fly-camera capture
 * ({@code cursorCaught}/{@code fixedCam}, used by both board modes) and passes them in; a {@code rebuild} callback
 * re-solves the circuit when an interaction changes it. Verified via the scripted-input harness.
 */
final class SnapEditorController {
    private final PhysicalBoardView physWorld;
    private final PhysicalEditor physEditor;
    private final FreeCameraController flyCam;
    private final PerspectiveCamera camera;
    private final Runnable rebuild;

    // Owned interactive state (read by the screen's input handler, status line and harness adapter).
    Focus physFocus;         // what the crosshair is over
    Focus grabbed;           // a sub-part being dragged (LMB held)
    boolean scriptLmbHeld;   // the harness's "LMB is held" (Gdx.input can't be faked)
    int outlineSegs;         // probe: segments drawn by the last focus outline

    private ShapeRenderer outline; // Minecraft-style focus highlight (lazy)

    SnapEditorController(PhysicalBoardView physWorld, PhysicalEditor physEditor, FreeCameraController flyCam,
                         PerspectiveCamera camera, Runnable rebuild) {
        this.physWorld = physWorld;
        this.physEditor = physEditor;
        this.flyCam = flyCam;
        this.camera = camera;
        this.rebuild = rebuild;
    }

    /**
     * One physical-mode frame of editing: while GRABBING a knob the camera keeps turning FREELY and the knob
     * FOLLOWS the crosshair (line of sight) — it does NOT lock the view; LMB released → grab ends in touchUp.
     * Momentary controls spring back toward rest when not held. The placement ghost is suppressed while hovering
     * or dragging an interactive sub-part. {@code cursorCaught}/{@code fixedCam} are the screen's shared fly-cam
     * capture state.
     */
    void update(float dt, boolean cursorCaught, boolean fixedCam) {
        boolean grabbing = grabbed != null
                && (Gdx.input.isButtonPressed(Input.Buttons.LEFT) || scriptLmbHeld);
        if (!grabbing) grabbed = null;
        flyCam.setLookEnabled(cursorCaught && !fixedCam);
        flyCam.update(dt);
        physEditor.update(camera, physWorld);
        Ray cross = camera.getPickRay(Gdx.graphics.getWidth() / 2f, Gdx.graphics.getHeight() / 2f);
        physFocus = physWorld.focusAt(cross);
        if (grabbing && physWorld.aimSubPart(grabbed, cross)) {
            rebuild.run(); // the knob followed the aim → re-solve
        }
        // Momentary controls (push-buttons) spring back toward rest when not held; re-solve as they open.
        if (physWorld.tickMomentary(dt, grabbing ? grabbed.placementIndex() : -1)) {
            rebuild.run();
        }
        // Suppress the placement ghost while hovering (or dragging) an interactive sub-part — LMB interacts.
        boolean interactive = grabbing || physWorld.isInteractive(physFocus);
        physWorld.setGhost(!interactive && physEditor.present() && cursorCaught, physEditor.modelId(),
                physEditor.ghostTransform(), physEditor.valid(), dt);
    }

    /** Minecraft-style highlight: outline whatever the crosshair is over — a placed part (black) or one of its
     *  movable sub-parts / knobs. {@link #physFocus} was computed in {@link #update}. */
    void drawOutline() {
        if (physFocus == null) {
            return;
        }
        if (outline == null) {
            outline = new ShapeRenderer();
        }
        // The model's OWN shape (crease edges) with hidden lines removed in software (ray-cast per sample), so
        // the lines sit EXACTLY on the edges — no depth test, no bias, no expansion (owner: not bloated).
        float[] seg = physWorld.focusEdges(physFocus, camera.position);
        outlineSegs = seg.length / 6; // probe: outline.segs
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST); // draw on top: visibility was already decided per sample
        outline.setProjectionMatrix(camera.combined);
        outline.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        outline.setColor(0f, 0f, 0f, 0.55f); // Minecraft's block outline: thin black line (a touch denser than MC's 40% — 1px on a Retina frame)
        for (int i = 0; i + 5 < seg.length; i += 6) {
            outline.line(seg[i], seg[i + 1], seg[i + 2], seg[i + 3], seg[i + 4], seg[i + 5]);
        }
        outline.end();
    }
}
