package com.minecart.display.render;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.viewport.ScreenViewport;

/**
 * Desmos-style coordinate grid drawn behind every other actor on a {@link WorldStage}. Adapts the grid step
 * to the current camera zoom so 5..15 major lines are visible regardless of magnification, and emphasises
 * the X / Y axes (y=0, x=0) with a brighter blue.
 *
 * <p>Renders via a {@link ShapeRenderer} configured with the worldStage camera's projection — Scene2D's
 * SpriteBatch is briefly flushed in {@link #draw} so the lines composite correctly under any subsequent
 * actor draws.
 *
 * <p>Owns its {@link ShapeRenderer}; call {@link #dispose()} when the owning screen is shutting down so the
 * GPU resources are released alongside the rest of the screen's textures / fonts.
 */
public class GridBackgroundActor extends Actor implements Disposable {

    private static final Color MINOR = new Color(0.18f, 0.30f, 0.45f, 1f);
    private static final Color MAJOR = new Color(0.30f, 0.46f, 0.66f, 1f);
    private static final Color AXIS  = new Color(0.55f, 0.78f, 1.00f, 1f);

    /** Target number of major grid lines visible across the shorter screen dimension. Drives step picking. */
    private static final int TARGET_MAJORS = 10;
    private static final int MINOR_SUBDIVISIONS = 5;
    /**
     * Hard cap on grid lines drawn per axis per frame. {@link #pickMajorStep} keeps the real count near
     * {@link #TARGET_MAJORS}, so this only ever engages as a safety valve against a degenerate step that
     * would otherwise ask the GL thread to emit an unbounded number of lines.
     */
    private static final int MAX_LINES_PER_AXIS = 10_000;

    private final WorldStage worldStage;
    private final ShapeRenderer shapes = new ShapeRenderer();

    public GridBackgroundActor(WorldStage worldStage) {
        this.worldStage = worldStage;
    }

    @Override
    public void draw(com.badlogic.gdx.graphics.g2d.Batch batch, float parentAlpha) {
        // Scene2D draws all actors through a single SpriteBatch; ShapeRenderer is a separate pipeline, so we
        // pause the batch, draw lines under the camera projection, then resume so siblings render normally.
        Stage stage = getStage();
        if (stage == null) {
            return;
        }
        OrthographicCamera cam = worldStage.getCamera();
        ScreenViewport vp = (ScreenViewport) worldStage.getViewport();

        // World-space visible rectangle. With ScreenViewport, half-extents in world units are
        // screen_pixels * unitsPerPixel * zoom / 2.
        float halfW = vp.getScreenWidth() * vp.getUnitsPerPixel() * cam.zoom * 0.5f;
        float halfH = vp.getScreenHeight() * vp.getUnitsPerPixel() * cam.zoom * 0.5f;
        float left   = cam.position.x - halfW;
        float right  = cam.position.x + halfW;
        float bottom = cam.position.y - halfH;
        float top    = cam.position.y + halfH;

        float majorStep = pickMajorStep(Math.min(2 * halfW, 2 * halfH));
        float minorStep = majorStep / MINOR_SUBDIVISIONS;

        batch.end();
        shapes.setProjectionMatrix(cam.combined);
        shapes.begin(ShapeRenderer.ShapeType.Line);
        try {
            drawVerticals(left, right, bottom, top, minorStep, majorStep);
            drawHorizontals(left, right, bottom, top, minorStep, majorStep);

            // Axes drawn last so they sit on top of the major/minor lines they coincide with.
            shapes.setColor(AXIS);
            if (bottom <= 0f && top >= 0f) {
                shapes.line(left, 0f, right, 0f);
            }
            if (left <= 0f && right >= 0f) {
                shapes.line(0f, bottom, 0f, top);
            }
        } finally {
            shapes.end();
            batch.begin();
        }
    }

    private void drawVerticals(float left, float right, float bottom, float top,
                               float minorStep, float majorStep) {
        // Snap the iteration start to the nearest minor step boundary so the grid is stable as the camera
        // pans (instead of crawling with the camera). Loop with an integer index over a double start so
        // the step is never lost to float ULP at extreme zoom (a `for (float x = ...; x += step)` loop
        // never terminates once ulp(x) > step, hard-freezing the GL thread).
        double startMinor = Math.ceil(left / minorStep) * minorStep;
        int minorCount = stepCount(startMinor, right, minorStep);
        shapes.setColor(MINOR);
        for (int i = 0; i < minorCount; i++) {
            float x = (float) (startMinor + i * (double) minorStep);
            if (isMultipleOf(x, majorStep)) continue;
            shapes.line(x, bottom, x, top);
        }
        double startMajor = Math.ceil(left / majorStep) * majorStep;
        int majorCount = stepCount(startMajor, right, majorStep);
        shapes.setColor(MAJOR);
        for (int i = 0; i < majorCount; i++) {
            float x = (float) (startMajor + i * (double) majorStep);
            shapes.line(x, bottom, x, top);
        }
    }

    private void drawHorizontals(float left, float right, float bottom, float top,
                                 float minorStep, float majorStep) {
        double startMinor = Math.ceil(bottom / minorStep) * minorStep;
        int minorCount = stepCount(startMinor, top, minorStep);
        shapes.setColor(MINOR);
        for (int i = 0; i < minorCount; i++) {
            float y = (float) (startMinor + i * (double) minorStep);
            if (isMultipleOf(y, majorStep)) continue;
            shapes.line(left, y, right, y);
        }
        double startMajor = Math.ceil(bottom / majorStep) * majorStep;
        int majorCount = stepCount(startMajor, top, majorStep);
        shapes.setColor(MAJOR);
        for (int i = 0; i < majorCount; i++) {
            float y = (float) (startMajor + i * (double) majorStep);
            shapes.line(left, y, right, y);
        }
    }

    /**
     * Number of grid lines from {@code start} up to and including {@code end} at {@code step} spacing.
     * Computed as an integer count (not a float-accumulating loop bound) so extreme zoom / pan can never
     * produce a non-terminating loop. Clamped to a sane upper bound so a degenerate step never asks the
     * GL thread to draw millions of lines. Returns 0 for a non-finite or non-positive step.
     */
    static int stepCount(double start, double end, float step) {
        if (!Double.isFinite(start) || !Double.isFinite(end) || !(step > 0f) || end < start) {
            return 0;
        }
        long count = (long) Math.floor((end - start) / step) + 1L;
        if (count < 0L) {
            return 0;
        }
        return (int) Math.min(count, MAX_LINES_PER_AXIS);
    }

    /**
     * Picks an adaptive major step in world units. Snaps to {1, 2, 5} * 10^n so the grid lines land on
     * "nice" numbers (1, 2, 5, 10, 20, 50, 100, ...) that read naturally in the axis labels.
     */
    static float pickMajorStep(float viewSpan) {
        if (!Float.isFinite(viewSpan) || viewSpan <= 0f) {
            return 1f;
        }
        double rough = viewSpan / TARGET_MAJORS;
        double mag = Math.pow(10, Math.floor(Math.log10(rough)));
        double norm = rough / mag;
        double mult;
        if (norm < 1.5) {
            mult = 1;
        } else if (norm < 3.5) {
            mult = 2;
        } else if (norm < 7.5) {
            mult = 5;
        } else {
            mult = 10;
        }
        return (float) (mult * mag);
    }

    /**
     * True if {@code v} is (within a tiny tolerance) an integer multiple of {@code step}. Used to skip
     * minor grid lines that coincide with majors so the major colour wins.
     */
    private static boolean isMultipleOf(float v, float step) {
        double r = v / step;
        return Math.abs(r - Math.round(r)) < 1e-3;
    }

    @Override
    public void dispose() {
        shapes.dispose();
    }
}
