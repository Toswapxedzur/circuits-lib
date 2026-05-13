package com.minecart.registry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests {@link ComponentAnchorRegistry#worldPositionOf} rotation math and the {@link AllComponents}-side
 * {@code BJ_TRANSISTOR} registration so the renderer / placement handlers can rely on the static anchor data.
 */
class ComponentAnchorRegistryTest {

    private static final double EPS = 1e-9;

    @Test
    void worldPositionOf_zeroAngle_translatesByCentre() {
        ComponentAnchorRegistry.Anchor a = new ComponentAnchorRegistry.Anchor(0, 1.0, 0.0);
        double[] xy = ComponentAnchorRegistry.worldPositionOf(a, 5.0, 7.0, 0.0);
        assertEquals(6.0, xy[0], EPS);
        assertEquals(7.0, xy[1], EPS);
    }

    @Test
    void worldPositionOf_quarterTurn_rotates_xToY() {
        ComponentAnchorRegistry.Anchor a = new ComponentAnchorRegistry.Anchor(0, 1.0, 0.0);
        double[] xy = ComponentAnchorRegistry.worldPositionOf(a, 0.0, 0.0, Math.PI / 2.0);
        assertEquals(0.0, xy[0], EPS);
        assertEquals(1.0, xy[1], EPS);
    }

    @Test
    void worldPositionOf_halfTurn_negatesOffset() {
        ComponentAnchorRegistry.Anchor a = new ComponentAnchorRegistry.Anchor(0, 1.0, 2.0);
        double[] xy = ComponentAnchorRegistry.worldPositionOf(a, 10.0, 20.0, Math.PI);
        assertEquals(9.0, xy[0], EPS);
        assertEquals(18.0, xy[1], EPS);
    }

    @Test
    void worldPositionOf_arbitraryAngle_matchesRotationFormula() {
        ComponentAnchorRegistry.Anchor a = new ComponentAnchorRegistry.Anchor(0, 1.5, -0.5);
        double cx = -3.0;
        double cy = 4.0;
        double angle = Math.toRadians(30.0);
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        double expectedX = cx + a.offsetX() * cos - a.offsetY() * sin;
        double expectedY = cy + a.offsetX() * sin + a.offsetY() * cos;
        double[] xy = ComponentAnchorRegistry.worldPositionOf(a, cx, cy, angle);
        assertEquals(expectedX, xy[0], EPS);
        assertEquals(expectedY, xy[1], EPS);
    }

    @Test
    void bjTransistor_anchors_areRegistered() {
        // Touch AllComponents so its static initializer runs before we read the registry.
        assertNotNull(AllComponents.BJ_TRANSISTOR);
        var anchors = ComponentAnchorRegistry.getAnchors(AllComponents.BJ_TRANSISTOR);
        assertEquals(3, anchors.size());
        // base @ port 0
        assertEquals(0, anchors.get(0).portIndex());
        assertEquals(-1.0, anchors.get(0).offsetX(), EPS);
        assertEquals(0.0, anchors.get(0).offsetY(), EPS);
        // collector @ port 1
        assertEquals(1, anchors.get(1).portIndex());
        assertEquals(1.0, anchors.get(1).offsetX(), EPS);
        assertEquals(1.0, anchors.get(1).offsetY(), EPS);
        // emitter @ port 2
        assertEquals(2, anchors.get(2).portIndex());
        assertEquals(1.0, anchors.get(2).offsetX(), EPS);
        assertEquals(-1.0, anchors.get(2).offsetY(), EPS);
    }
}
