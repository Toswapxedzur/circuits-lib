package com.minecart.physics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sanity coverage of {@link Vec2}'s arithmetic. The vector type is value-class trivial, but the
 * solver leans on a few non-obvious operations ({@link Vec2#cross}, {@link Vec2#perpendicular},
 * {@link Vec2#rotate(double, double)}) — pinning those here lets a refactor of the inner math
 * fail in tests rather than only in the solver's downstream tolerance checks.
 */
class Vec2Test {

    private static final double EPS = 1e-12;

    @Test
    void add_subtract_and_scale_compose() {
        Vec2 a = new Vec2(1.0, 2.0);
        Vec2 b = new Vec2(-3.0, 5.0);

        Vec2 sum = a.add(b);
        assertEquals(-2.0, sum.x(), EPS);
        assertEquals(7.0, sum.y(), EPS);

        Vec2 diff = a.sub(b);
        assertEquals(4.0, diff.x(), EPS);
        assertEquals(-3.0, diff.y(), EPS);

        Vec2 scaled = a.scale(2.5);
        assertEquals(2.5, scaled.x(), EPS);
        assertEquals(5.0, scaled.y(), EPS);
    }

    @Test
    void dot_and_cross_match_textbook_2D() {
        Vec2 a = new Vec2(3.0, 4.0);
        Vec2 b = new Vec2(1.0, 2.0);

        assertEquals(3.0 * 1.0 + 4.0 * 2.0, a.dot(b), EPS);
        // 2D cross product (z-component of the 3D cross product): a.x*b.y - a.y*b.x
        assertEquals(3.0 * 2.0 - 4.0 * 1.0, a.cross(b), EPS);
    }

    @Test
    void perpendicular_rotates_90deg_CCW() {
        // (1, 0) should become (0, 1) when rotated 90° CCW.
        Vec2 e1 = new Vec2(1.0, 0.0);
        Vec2 perp = e1.perpendicular();
        assertEquals(0.0, perp.x(), EPS);
        assertEquals(1.0, perp.y(), EPS);
    }

    @Test
    void length_and_normalised_handle_zero_safely() {
        Vec2 zero = new Vec2(0.0, 0.0);
        assertEquals(0.0, zero.length(), EPS);
        // The contract is "zero in, zero out" — callers must check non-degeneracy themselves.
        assertEquals(Vec2.ZERO, zero.normalised());

        Vec2 v = new Vec2(3.0, 4.0);
        assertEquals(5.0, v.length(), EPS);
        Vec2 n = v.normalised();
        assertEquals(0.6, n.x(), EPS);
        assertEquals(0.8, n.y(), EPS);
        // A normalised vector has unit length (modulo numerical error from the sqrt).
        assertEquals(1.0, n.length(), EPS);
    }

    @Test
    void rotate_zero_is_identity_and_pi_flips() {
        Vec2 v = new Vec2(2.0, 3.0);
        Vec2 r0 = v.rotate(0.0);
        assertEquals(v.x(), r0.x(), EPS);
        assertEquals(v.y(), r0.y(), EPS);

        Vec2 rPi = v.rotate(Math.PI);
        assertEquals(-v.x(), rPi.x(), 1e-9);
        assertEquals(-v.y(), rPi.y(), 1e-9);

        Vec2 rHalfPi = v.rotate(Math.PI / 2.0);
        assertEquals(-v.y(), rHalfPi.x(), 1e-9);
        assertEquals(v.x(), rHalfPi.y(), 1e-9);
    }

    @Test
    void rotate_precomputed_cos_sin_matches_radian_form() {
        Vec2 v = new Vec2(1.5, -2.5);
        double radians = 1.23;
        Vec2 fromRad = v.rotate(radians);
        Vec2 fromCs = v.rotate(Math.cos(radians), Math.sin(radians));
        // Same arithmetic both ways — bit-exact equality is allowed.
        assertEquals(fromRad.x(), fromCs.x(), EPS);
        assertEquals(fromRad.y(), fromCs.y(), EPS);
    }

    @Test
    void equals_and_hashCode_value_semantics() {
        Vec2 a = new Vec2(1.0, 2.0);
        Vec2 b = new Vec2(1.0, 2.0);
        Vec2 c = new Vec2(1.0, 2.1);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);

        // Bit-pattern equality: NaN compares equal to itself (not the IEEE-754 default behaviour
        // of normal `==`), so the type is safely usable as a map key.
        Vec2 nan = new Vec2(Double.NaN, 0.0);
        Vec2 nan2 = new Vec2(Double.NaN, 0.0);
        assertEquals(nan, nan2);

        // And +0 / -0 hash differently (also bit-pattern), so callers can disambiguate signed
        // zeros if they ever matter.
        Vec2 zPlus = new Vec2(0.0, 0.0);
        Vec2 zMinus = new Vec2(-0.0, 0.0);
        assertTrue(zPlus.x() == zMinus.x()); // numerical equality holds
        assertNotEquals(zPlus, zMinus);       // value-class equality is bit-pattern-strict
    }
}
