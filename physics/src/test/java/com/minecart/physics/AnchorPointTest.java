package com.minecart.physics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins {@link AnchorPoint}'s world-position tracking against body pose mutations. The constraint
 * code leans heavily on the "anchor recomputes world position on every read" invariant; if
 * that's accidentally cached at construction time the entire solver silently mispredicts.
 */
class AnchorPointTest {

    private static final double EPS = 1e-12;

    @Test
    void anchor_at_centre_follows_body_translation() {
        Body body = Body.free("b", new Vec2(0.0, 0.0));
        AnchorPoint anchor = AnchorPoint.atCentre(body);
        assertEquals(new Vec2(0.0, 0.0), anchor.worldPosition());

        body.translate(new Vec2(3.0, 4.0));
        assertEquals(new Vec2(3.0, 4.0), anchor.worldPosition());
    }

    @Test
    void anchor_with_local_offset_applies_body_rotation_first_then_translation() {
        // Body at (10, 0). Anchor with local offset (1, 0). Body unrotated → world (11, 0).
        // Rotate body 90° CCW around its centre. Anchor world position should be (10, 1).
        Body body = Body.free("b", new Vec2(10.0, 0.0));
        AnchorPoint anchor = new AnchorPoint(body, new Vec2(1.0, 0.0));
        assertEquals(new Vec2(11.0, 0.0), anchor.worldPosition());

        body.rotate(Math.PI / 2.0);
        assertEquals(10.0, anchor.worldPosition().x(), 1e-9);
        assertEquals(1.0, anchor.worldPosition().y(), 1e-9);
    }

    @Test
    void anchor_on_rotation_free_body_orbits_around_pivot() {
        // Body at (3, 0) pivoted at the origin. Anchor at body's centre (local offset (0,0)).
        // Rotate body 180°: the body's position should flip to (-3, 0), and so should the anchor.
        Body body = Body.pivoted("r", new Vec2(3.0, 0.0), new Vec2(0.0, 0.0));
        AnchorPoint anchor = AnchorPoint.atCentre(body);
        assertEquals(new Vec2(3.0, 0.0), anchor.worldPosition());

        body.rotate(Math.PI);
        assertEquals(-3.0, anchor.worldPosition().x(), 1e-9);
        assertEquals(0.0, anchor.worldPosition().y(), 1e-9);
    }

    @Test
    void anchor_recomputes_world_position_each_call_not_cached() {
        Body body = Body.free("b", Vec2.ZERO);
        AnchorPoint anchor = AnchorPoint.atCentre(body);

        Vec2 first = anchor.worldPosition();
        body.setPosition(new Vec2(42.0, 42.0));
        Vec2 second = anchor.worldPosition();

        assertEquals(Vec2.ZERO, first);
        assertEquals(new Vec2(42.0, 42.0), second);
    }
}
