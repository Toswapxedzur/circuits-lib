package com.minecart.display.render.registry.parts;

import com.badlogic.gdx.graphics.Texture;
import com.minecart.display.render.EdgeActor;
import com.minecart.display.render.registry.CurrentRenderColors;
import com.minecart.display.render.registry.RenderContext;
import com.minecart.display.render.registry.RenderPart;
import com.minecart.logic.CircuitEdge;

/** Animated fixed-size dash overlay showing current direction and magnitude. */
public final class EdgeCurrentFlowPart implements RenderPart<CircuitEdge> {

    private static final double CURRENT_EPSILON = 1e-9;
    private static final float DASH_LENGTH = 0.35f;
    private static final float GAP_LENGTH = 0.25f;
    private static final float DASH_THICKNESS = EdgeActor.THICKNESS * 0.18f;
    private static final float MIN_DASH_SPEED = 0.25f;
    private static final float MAX_DASH_SPEED = 3.5f;
    private static final float CURRENT_SPEED_SCALE = 1.0f;

    @Override
    public void draw(CircuitEdge edge, RenderContext context) {
        double current = edge.getCurrent().getValue();
        if (Math.abs(current) <= CURRENT_EPSILON) {
            return;
        }
        Texture bodyTex = context.textures().getById(edge.getRegistryTypeId());
        EdgeGeometry g = EdgeGeometry.of(edge, EdgeGeometry.naturalBodyLength(bodyTex));
        if (g == null || g.len < DASH_LENGTH) {
            return;
        }

        float period = DASH_LENGTH + GAP_LENGTH;
        float intensity = context.electricalStats().edgeIntensity(edge);
        float phase = (context.timeSeconds() * dashSpeedForCurrent(current)) % period;
        float dir = current >= 0.0 ? 1f : -1f;
        float ux = g.ux * dir;
        float uy = g.uy * dir;
        float startX = dir > 0f ? g.ax : g.bx;
        float startY = dir > 0f ? g.ay : g.by;
        float angleDeg = (float) Math.toDegrees(Math.atan2(uy, ux));

        Texture pixel = context.textures().whitePixel();
        var color = CurrentRenderColors.colorFor(intensity, 0.92f);
        var batch = context.batch();
        batch.setColor(color.r, color.g, color.b, color.a * context.parentAlpha());
        for (float s = phase; s + DASH_LENGTH <= g.len; s += period) {
            float x = startX + ux * s;
            float y = startY + uy * s;
            batch.draw(pixel,
                    x, y - DASH_THICKNESS / 2f,
                    0f, DASH_THICKNESS / 2f,
                    DASH_LENGTH, DASH_THICKNESS,
                    1f, 1f,
                    angleDeg,
                    0, 0,
                    pixel.getWidth(), pixel.getHeight(),
                    false, false);
        }
        batch.setColor(1f, 1f, 1f, 1f);
    }

    static float dashSpeedForCurrent(double current) {
        double magnitude = Math.abs(current);
        if (magnitude <= CURRENT_EPSILON) {
            return 0f;
        }
        float speed = MIN_DASH_SPEED + (float) magnitude * CURRENT_SPEED_SCALE;
        return Math.min(MAX_DASH_SPEED, speed);
    }
}
