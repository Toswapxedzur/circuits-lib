package com.minecart.physics;

/**
 * Immutable 2D vector. Pure value type — every operation returns a new instance rather than
 * mutating in place, so we don't accidentally share references across constraints / bodies. The
 * allocations are deliberate: each {@code Vec2} is small enough (two {@code double}s) that the JIT
 * routinely scalarises them and elides the heap allocation entirely. Profiling at higher iteration
 * counts has not shown this to be a bottleneck for the solver scale this module targets (under a
 * few thousand constraints).
 *
 * <p>Equality / hash uses bitwise double comparison ({@link Double#doubleToLongBits}) so
 * {@code +0.0} and {@code -0.0} hash differently and {@code NaN} compares equal to itself — both
 * deliberate choices to keep the type a clean value object usable as a map key in tests.
 */
public final class Vec2 {

    public static final Vec2 ZERO = new Vec2(0.0, 0.0);

    private final double x;
    private final double y;

    public Vec2(double x, double y) {
        this.x = x;
        this.y = y;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public Vec2 add(Vec2 other) {
        return new Vec2(x + other.x, y + other.y);
    }

    public Vec2 sub(Vec2 other) {
        return new Vec2(x - other.x, y - other.y);
    }

    public Vec2 scale(double s) {
        return new Vec2(x * s, y * s);
    }

    public double dot(Vec2 other) {
        return x * other.x + y * other.y;
    }

    /** Z component of the 3D cross product treating {@code this} and {@code other} as 2D vectors. */
    public double cross(Vec2 other) {
        return x * other.y - y * other.x;
    }

    /** Counter-clockwise perpendicular: {@code (x, y) -> (-y, x)}. Unit length preserved. */
    public Vec2 perpendicular() {
        return new Vec2(-y, x);
    }

    public double length() {
        return Math.sqrt(x * x + y * y);
    }

    public double lengthSquared() {
        return x * x + y * y;
    }

    /**
     * Returns a unit vector in the same direction. For a zero-length input the result is also
     * {@code (0, 0)} — callers that care about non-degenerate normals must check {@link #length()}
     * first.
     */
    public Vec2 normalised() {
        double len = length();
        if (len == 0.0) {
            return ZERO;
        }
        return new Vec2(x / len, y / len);
    }

    /**
     * Rotates this vector by {@code radians} around the origin. Convenience wrapper around the
     * pre-computed {@link #rotate(double, double)} form for callers that don't have the trig
     * values cached.
     */
    public Vec2 rotate(double radians) {
        return rotate(Math.cos(radians), Math.sin(radians));
    }

    /**
     * Rotates this vector by the angle whose cosine and sine are given. Used in hot loops where
     * {@code (cos, sin)} have already been computed once for many vectors (the standard body-pose
     * application path); avoids re-evaluating the trig functions per-vector.
     */
    public Vec2 rotate(double cos, double sin) {
        return new Vec2(x * cos - y * sin, x * sin + y * cos);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Vec2 v)) return false;
        return Double.doubleToLongBits(x) == Double.doubleToLongBits(v.x)
                && Double.doubleToLongBits(y) == Double.doubleToLongBits(v.y);
    }

    @Override
    public int hashCode() {
        long xb = Double.doubleToLongBits(x);
        long yb = Double.doubleToLongBits(y);
        int h = (int) (xb ^ (xb >>> 32));
        h = 31 * h + (int) (yb ^ (yb >>> 32));
        return h;
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ")";
    }
}
