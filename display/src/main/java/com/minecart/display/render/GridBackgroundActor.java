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
        // pans (instead of crawling with the camera).
        float startMinor = (float) (Math.ceil(left / minorStep) * minorStep);
        shapes.setColor(MINOR);
        for (float x = startMinor; x <= right; x += minorStep) {
            if (isMultipleOf(x, majorStep)) continue;
            shapes.line(x, bottom, x, top);
        }
        float startMajor = (float) (Math.ceil(left / majorStep) * majorStep);
        shapes.setColor(MAJOR);
        for (float x = startMajor; x <= right; x += majorStep) {
            shapes.line(x, bottom, x, top);
        }
    }

    private void drawHorizontals(float left, float right, float bottom, float top,
                                 float minorStep, float majorStep) {
        float startMinor = (float) (Math.ceil(bottom / minorStep) * minorStep);
        shapes.setColor(MINOR);
        for (float y = startMinor; y <= top; y += minorStep) {
            if (isMultipleOf(y, majorStep)) continue;
            shapes.line(left, y, right, y);
        }
        float startMajor = (float) (Math.ceil(bottom / majorStep) * majorStep);
        shapes.setColor(MAJOR);
        for (float y = startMajor; y <= top; y += majorStep) {
            shapes.line(left, y, right, y);
        }
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
