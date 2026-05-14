package com.minecart.display.render;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.utils.viewport.ScreenViewport;

/**
 * Screen-space tick labels for the world-space coordinate grid.
 *
 * <p>Lives on the UI stage (not the world stage) so it can render in raw pixel coordinates instead of
 * world units. Every frame it reads the {@link WorldStage}'s camera, walks the same major-tick set
 * {@link GridBackgroundActor} would have produced, and draws each tick number in white. The labels stick
 * to the visible viewport edge when their axis scrolls off-screen — so e.g. the X-tick labels stay at the
 * top or bottom edge if the user pans the X-axis out of view, giving a permanent readout of where they
 * are even when the actual axes aren't on screen.
 *
 * <p>Numbers exactly at zero are skipped on each axis to avoid overlap with the single "0" drawn at the
 * (possibly clamped) origin position.
 */
public class AxisLabelsActor extends Actor {

    private static final float DEFAULT_EDGE_PADDING_PX = 18f;
    private static final float TEXT_NUDGE_PX = 3f;

    private final WorldStage worldStage;
    private final BitmapFont font;
    /** Reused per-frame to measure label widths for right-edge mirroring; avoids per-tick allocations. */
    private final GlyphLayout layout = new GlyphLayout();

    /**
     * Extra pixels reserved at each viewport edge so labels park above/below the chrome (top bar, bottom
     * palette) instead of getting visually clipped by it. Configured by {@link com.minecart.display.screen.GameScreen}
     * to match the heights of its top + bottom bars.
     */
    private float insetTop = DEFAULT_EDGE_PADDING_PX;
    private float insetBottom = DEFAULT_EDGE_PADDING_PX;
    private float insetLeft = DEFAULT_EDGE_PADDING_PX;
    private float insetRight = DEFAULT_EDGE_PADDING_PX;

    public AxisLabelsActor(WorldStage worldStage, BitmapFont font) {
        this.worldStage = worldStage;
        this.font = font;
    }

    /**
     * Sets the per-edge "safe" inset (in screen pixels). The X-tick label row stays at least {@code top}
     * pixels below the top edge and {@code bottom} pixels above the bottom edge; the Y-tick column stays
     * at least {@code left}/{@code right} pixels from the side edges. The "0" origin label uses the same
     * clamped position, so it never disappears under the palette either.
     */
    public void setInsets(float top, float bottom, float left, float right) {
        this.insetTop = top;
        this.insetBottom = bottom;
        this.insetLeft = left;
        this.insetRight = right;
    }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        OrthographicCamera cam = worldStage.getCamera();
        ScreenViewport vp = (ScreenViewport) worldStage.getViewport();
        float screenW = vp.getScreenWidth();
        float screenH = vp.getScreenHeight();
        if (screenW <= 0f || screenH <= 0f) {
            return;
        }
        float upp = vp.getUnitsPerPixel();
        float halfW = screenW * upp * cam.zoom * 0.5f;
        float halfH = screenH * upp * cam.zoom * 0.5f;
        float left   = cam.position.x - halfW;
        float right  = cam.position.x + halfW;
        float bottom = cam.position.y - halfH;
        float top    = cam.position.y + halfH;

        // Use the same step picker as the grid so labels always match the brightest lines on screen.
        float majorStep = GridBackgroundActor.pickMajorStep(Math.min(2 * halfW, 2 * halfH));

        // Project the world origin to screen space. When y=0 is off-screen, the projected y is outside
        // [0, screenH]; we clamp to a padding band so the row of X-tick labels stays parked at whichever
        // edge the axis exited (top vs bottom). Same idea for the Y-axis labels in X.
        Vector3 origin = new Vector3(0f, 0f, 0f);
        cam.project(origin);
        // Remember whether the y-axis was clamped against the right edge (origin off-screen to the right)
        // BEFORE the clamp() call mutates the value — used below to flip the Y-tick label anchor so the
        // glyphs stay inside the viewport instead of running past the right edge.
        boolean yAxisClampedRight = origin.x > screenW - insetRight;
        float xAxisLabelY = clamp(origin.y, insetBottom, screenH - insetTop);
        float yAxisLabelX = clamp(origin.x, insetLeft, screenW - insetRight);

        font.setColor(Color.WHITE);

        // X axis: numbers labelled at major world x ticks.
        double startX = Math.ceil(left / majorStep) * majorStep;
        for (double x = startX; x <= right + 1e-6; x += majorStep) {
            if (Math.abs(x) < majorStep * 1e-3) {
                continue;
            }
            Vector3 v = new Vector3((float) x, 0f, 0f);
            cam.project(v);
            font.draw(batch, formatTick(x, majorStep), v.x + TEXT_NUDGE_PX, xAxisLabelY - TEXT_NUDGE_PX);
        }

        // Y axis: numbers labelled at major world y ticks.
        // When the axis is clamped to the right edge we right-align the labels (anchor at the right side
        // of the text instead of the left) so the glyphs sit *inside* the viewport. Without this they
        // would draw rightward from a column that's only ~insetRight away from the screen edge and the
        // last few characters of multi-digit labels (e.g. "-100") would fall off-screen.
        double startY = Math.ceil(bottom / majorStep) * majorStep;
        for (double y = startY; y <= top + 1e-6; y += majorStep) {
            if (Math.abs(y) < majorStep * 1e-3) {
                continue;
            }
            Vector3 v = new Vector3(0f, (float) y, 0f);
            cam.project(v);
            String s = formatTick(y, majorStep);
            float lx;
            if (yAxisClampedRight) {
                layout.setText(font, s);
                lx = yAxisLabelX - TEXT_NUDGE_PX - layout.width;
            } else {
                lx = yAxisLabelX + TEXT_NUDGE_PX;
            }
            font.draw(batch, s, lx, v.y - TEXT_NUDGE_PX);
        }

        // Single "0" at the (clamped) origin for orientation. Drawn once instead of twice so the two axes
        // don't double-up labels at the same pixel. Mirror its anchor too when the y-axis is clamped right.
        float zeroX;
        if (yAxisClampedRight) {
            layout.setText(font, "0");
            zeroX = yAxisLabelX - TEXT_NUDGE_PX - layout.width;
        } else {
            zeroX = yAxisLabelX + TEXT_NUDGE_PX;
        }
        font.draw(batch, "0", zeroX, xAxisLabelY - TEXT_NUDGE_PX);
    }

    /**
     * Formats a tick value in the smallest readable form. Drops trailing zeros after a decimal point so
     * "1.0" prints as "1" and "0.5" prints as "0.5"; uses the major step's magnitude to pick precision so
     * fractional steps still render correctly.
     */
    static String formatTick(double value, float majorStep) {
        // Pick enough decimal places to distinguish neighbouring ticks; e.g. step 0.1 -> 1 decimal.
        int decimals = Math.max(0, (int) Math.ceil(-Math.log10(majorStep)));
        String s = String.format(java.util.Locale.ROOT, "%." + decimals + "f", value);
        if (decimals > 0 && s.contains(".")) {
            int end = s.length();
            while (end > 1 && s.charAt(end - 1) == '0') end--;
            if (end > 1 && s.charAt(end - 1) == '.') end--;
            s = s.substring(0, end);
        }
        if ("-0".equals(s)) {
            s = "0";
        }
        return s;
    }

    private static float clamp(float v, float lo, float hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }
}
