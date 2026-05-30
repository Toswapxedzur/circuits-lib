package com.minecart.variant.info;

/**
 * Immutable snapshot of a lock state: the {@link LockMode} plus, when meaningful, the world-space
 * rotation pivot. Returned by the soft-lock derivation on {@link com.minecart.logic.CircuitComponent}
 * and {@link com.minecart.logic.CircuitEdge}, and by the effective-lock combiner that AND-merges
 * strict and soft.
 *
 * <p>The pivot fields are only meaningful when {@link #mode} is {@link LockMode#PIVOTED} (and
 * defensively also for {@link LockMode#FREE} when a gesture wants a default rotation pivot); other
 * callers must ignore them.
 *
 * <p>{@code pivotValid} disambiguates "pivot at world origin" from "no pivot stored". Set to
 * {@code true} only when the pivot fields carry an authored or derived value.
 */
public record LockState(LockMode mode, double pivotX, double pivotY, boolean pivotValid) {

    public static final LockState FREE = new LockState(LockMode.FREE, 0.0, 0.0, false);
    public static final LockState LOCKED = new LockState(LockMode.LOCKED, 0.0, 0.0, false);

    public static LockState pivoted(double pivotX, double pivotY) {
        return new LockState(LockMode.PIVOTED, pivotX, pivotY, true);
    }

    public static LockState oriented() {
        return new LockState(LockMode.ORIENTED, 0.0, 0.0, false);
    }

    /**
     * Combines two lock states via {@link LockMode#and(LockMode, LockMode)} with the additional
     * pivot-reconciliation rule: if both inputs are {@link LockMode#PIVOTED} but their pivots
     * differ (within {@code epsilon}), the result is {@link LockMode#LOCKED} — the two rotation
     * axes can't both be honoured simultaneously, so no motion is permitted.
     */
    public static LockState and(LockState a, LockState b, double epsilon) {
        LockMode combined = LockMode.and(a.mode(), b.mode());
        if (combined == LockMode.PIVOTED) {
            // Both inputs must be PIVOTED since LockMode.and only outputs PIVOTED when
            // both are PIVOTED (FREE ∩ PIVOTED = PIVOTED, but FREE has no pivot,
            // so we honour whichever input does). If both have valid pivots and disagree → LOCKED.
            boolean aHas = a.mode() == LockMode.PIVOTED && a.pivotValid();
            boolean bHas = b.mode() == LockMode.PIVOTED && b.pivotValid();
            if (aHas && bHas) {
                if (Math.abs(a.pivotX() - b.pivotX()) > epsilon
                        || Math.abs(a.pivotY() - b.pivotY()) > epsilon) {
                    return LOCKED;
                }
                return pivoted(a.pivotX(), a.pivotY());
            }
            if (aHas) {
                return pivoted(a.pivotX(), a.pivotY());
            }
            if (bHas) {
                return pivoted(b.pivotX(), b.pivotY());
            }
            // PIVOTED without any authored pivot — defer the default-pivot decision to the
            // caller (component centre / edge midpoint), which has the geometry context.
            return new LockState(LockMode.PIVOTED, 0.0, 0.0, false);
        }
        if (combined == LockMode.ORIENTED) {
            return oriented();
        }
        if (combined == LockMode.FREE) {
            return FREE;
        }
        return LOCKED;
    }
}
