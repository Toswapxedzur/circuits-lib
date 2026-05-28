package com.minecart.physics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the {@link Body} primitive's invariants: pose mutation, inverse-inertia gating
 * ({@code invMass == 0} → no motion regardless of caller delta), pivot-based rotation, and the
 * four canonical lock-mode preset factories.
 */
class BodyTest {

    private static final double EPS = 1e-12;

    @Test
    void free_body_factory_carries_unit_inverse_inertias_and_pivot_equals_position() {
        Body body = Body.free("b", new Vec2(2.0, 3.0));

        assertEquals(1.0, body.invMassT(), EPS);
        assertEquals(1.0, body.invMassR(), EPS);
        assertEquals(new Vec2(2.0, 3.0), body.position());
        assertEquals(body.position(), body.rotationPivot());
    }

    @Test
    void locked_body_has_zero_inverse_inertia_so_translate_and_rotate_are_no_ops() {
        Body body = Body.locked("anchor", new Vec2(0.0, 0.0));

        body.translate(new Vec2(5.0, 7.0));
        body.rotate(Math.PI);

        // Position and angle unchanged: the inverse-inertia scaling absorbed both deltas.
        assertEquals(Vec2.ZERO, body.position());
        assertEquals(0.0, body.angle(), EPS);
    }

    @Test
    void position_free_body_translates_but_does_not_rotate() {
        Body body = Body.positionFree("p", new Vec2(1.0, 1.0));

        body.translate(new Vec2(2.0, 3.0));
        // Position advanced by exactly the delta (unit inverse mass).
        assertEquals(new Vec2(3.0, 4.0), body.position());

        double angleBefore = body.angle();
        body.rotate(0.5);
        // Rotation is fully absorbed by zero rotational inverse inertia.
        assertEquals(angleBefore, body.angle(), EPS);
    }

    @Test
    void rotation_free_body_rotates_around_pivot_only() {
        // Body at (3, 0) pivoted at the origin: rotating by π/2 CCW should move it to (0, 3).
        Body body = Body.rotationFree("r", new Vec2(3.0, 0.0), new Vec2(0.0, 0.0));

        body.translate(new Vec2(1.0, 1.0));
        // Translation is fully absorbed: still at (3, 0).
        assertEquals(new Vec2(3.0, 0.0), body.position());

        body.rotate(Math.PI / 2.0);
        assertEquals(0.0, body.position().x(), 1e-9);
        assertEquals(3.0, body.position().y(), 1e-9);
        assertEquals(Math.PI / 2.0, body.angle(), EPS);

        // Pivot itself never moves; the body orbits around it.
        assertEquals(new Vec2(0.0, 0.0), body.rotationPivot());
    }

    @Test
    void translate_scales_by_invMassT_for_weighted_bodies() {
        Body body = new Body("w", new Vec2(0.0, 0.0), 0.0, new Vec2(0.0, 0.0),
                /* invMassT */ 0.25, /* invMassR */ 1.0);

        body.translate(new Vec2(4.0, 8.0));
        // Delta scaled by 0.25 → (1.0, 2.0).
        assertEquals(new Vec2(1.0, 2.0), body.position());
    }

    @Test
    void rotate_scales_by_invMassR_for_weighted_bodies() {
        Body body = new Body("w", new Vec2(2.0, 0.0), 0.0, new Vec2(0.0, 0.0),
                /* invMassT */ 1.0, /* invMassR */ 0.5);

        // Request a π rotation; with invMassR=0.5 the effective rotation is π/2.
        body.rotate(Math.PI);
        assertEquals(Math.PI * 0.5, body.angle(), EPS);
        assertEquals(0.0, body.position().x(), 1e-9);
        assertEquals(2.0, body.position().y(), 1e-9);
    }

    @Test
    void localToWorld_applies_rotation_then_translation() {
        // Body at (10, 0) with angle = π/2 should map local (1, 0) to world (10, 1):
        //   R(π/2) * (1, 0) = (0, 1),  then + (10, 0) = (10, 1).
        Body body = new Body("lt", new Vec2(10.0, 0.0), Math.PI / 2.0, new Vec2(10.0, 0.0),
                1.0, 1.0);
        Vec2 world = body.localToWorld(new Vec2(1.0, 0.0));
        assertEquals(10.0, world.x(), 1e-9);
        assertEquals(1.0, world.y(), 1e-9);
    }

    @Test
    void rotation_with_pivot_at_position_only_advances_angle() {
        // A body whose pivot coincides with its position rotates around itself: position stays
        // put (the pivot is the position) and only the angle advances.
        Body body = Body.free("r", new Vec2(5.0, 5.0));
        body.rotate(1.234);
        assertEquals(new Vec2(5.0, 5.0), body.position());
        assertEquals(1.234, body.angle(), EPS);
    }

    @Test
    void negative_inverse_inertia_is_rejected_at_construction_and_setter() {
        assertThrows(IllegalArgumentException.class,
                () -> new Body("x", Vec2.ZERO, 0.0, Vec2.ZERO, -1.0, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new Body("x", Vec2.ZERO, 0.0, Vec2.ZERO, 1.0, -1.0));

        Body body = Body.free("x", Vec2.ZERO);
        assertThrows(IllegalArgumentException.class, () -> body.setInvMassT(-0.001));
        assertThrows(IllegalArgumentException.class, () -> body.setInvMassR(-0.001));
    }

    @Test
    void bodies_compare_by_id_only_not_by_pose() {
        Body a1 = Body.free("a", new Vec2(0.0, 0.0));
        Body a2 = Body.free("a", new Vec2(1.0, 1.0));
        Body b = Body.free("b", new Vec2(0.0, 0.0));

        assertEquals(a1, a2);
        assertEquals(a1.hashCode(), a2.hashCode());
        assertNotEquals(a1, b);
        // Sanity: different pose, same id, still equal — the identity is the id, not the pose.
        assertTrue(a1.equals(a2));
    }
}
