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

    private static final float DEFAULT_EDGE_PADDING_PX = 8f;
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

    /** Reusable projection scratch so the per-label loops don't allocate a new Vector3 every tick. */
    private final Vector3 proj = new Vector3();

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

        // Project the world origin once — gives us the raw, unclamped screen positions of the y-axis
        // (axisX) and x-axis (axisY) lines. Each label below uses these directly without ever sharing
        // a single clamped row/column position; that way per-label clamping can only ever affect that
        // label, never piling neighbours on top of one another.
        proj.set(0f, 0f, 0f);
        cam.project(proj);
        float axisX = proj.x;
        float axisY = proj.y;

        // Inner rectangle the labels prefer to stay inside.
        float safeL = insetLeft;
        float safeR = screenW - insetRight;
        float safeB = insetBottom;
        float safeT = screenH - insetTop;

        font.setColor(Color.WHITE);

        // X-axis tick labels. All labels in this row share the same y (= axisY). The y is clamped to
        // the safe band so the whole row stays sticky to the closest edge when the x-axis itself
        // scrolls off-screen. That's safe because the clamp value is identical for every label in the
        // row — it shifts them together, not on top of each other. No horizontal clamp: a single
        // tick straddling the right inset is allowed to overflow slightly rather than being pulled
        // back to where it could collide with its neighbour.
        double startX = Math.ceil(left / majorStep) * majorStep;
        for (double x = startX; x <= right + 1e-6; x += majorStep) {
            if (Math.abs(x) < majorStep * 1e-3) {
                continue;
            }
            proj.set((float) x, 0f, 0f);
            cam.project(proj);
            layout.setText(font, formatTick(x, majorStep));
            float w = layout.width;
            float h = layout.height;
            float lx = proj.x + TEXT_NUDGE_PX;
            float ly = axisY - TEXT_NUDGE_PX;
            if (ly > safeT) ly = safeT;
            if (ly - h < safeB) ly = safeB + h;
            // Cheap visibility skip — the loop bound already filters off-screen ticks in most zoom
            // levels, this catches the residual edge cases without a draw call cost.
            if (lx > screenW || lx + w < 0f) {
                continue;
            }
            font.draw(batch, layout, lx, ly);
        }

        // Y-axis tick labels. Vertical position is left at the tick's natural projected y so each
        // label sits next to its own tick; clamping vertically would map every off-band tick onto the
        // same band-edge pixel and stack them (the "lower-half stacking" bug). Horizontally, however,
        // the whole column shares one x (= axisX + nudge), so a clamp can never produce stacking —
        // it slides the entire column toward the safe edge together. We clamp here so labels stay
        // inside the right/left safe band as the y-axis approaches the edges (matches the "0"
        // origin label). This is NOT a mirror: the label keeps sliding along its own side of the
        // axis rather than flipping across it.
        double startY = Math.ceil(bottom / majorStep) * majorStep;
        for (double y = startY; y <= top + 1e-6; y += majorStep) {
            if (Math.abs(y) < majorStep * 1e-3) {
                continue;
            }
            proj.set(0f, (float) y, 0f);
            cam.project(proj);
            layout.setText(font, formatTick(y, majorStep));
            float w = layout.width;
            float h = layout.height;
            float lx = axisX + TEXT_NUDGE_PX;
            if (lx + w > safeR) lx = safeR - w;
            if (lx < safeL) lx = safeL;
            float ly = proj.y - TEXT_NUDGE_PX;
            // Skip labels whose rectangle is entirely outside the safe band vertically — hidden
            // behind chrome (top bar / palette).
            if (ly < safeB || ly - h > safeT) {
                continue;
            }
            font.draw(batch, layout, lx, ly);
        }

        // Single "0" at the origin. Only one of these exists, so we *can* clamp both dimensions
        // (sticky-corner behaviour) without ever risking a stack with neighbours. Stays right of the
        // y-axis like the other Y-tick labels — no mirror.
        layout.setText(font, "0");
        float w0 = layout.width;
        float h0 = layout.height;
        float lx0 = axisX + TEXT_NUDGE_PX;
        if (lx0 + w0 > safeR) lx0 = safeR - w0;
        if (lx0 < safeL) lx0 = safeL;
        float ly0 = axisY - TEXT_NUDGE_PX;
        if (ly0 > safeT) ly0 = safeT;
        if (ly0 - h0 < safeB) ly0 = safeB + h0;
        font.draw(batch, layout, lx0, ly0);
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
}
