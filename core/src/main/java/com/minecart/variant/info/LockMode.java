package com.minecart.variant.info;

/**
 * What kinds of motion a {@link com.minecart.logic.CircuitElement} permits. Used by {@link LockInfo}
 * (player-set "strict" lock state) and by the soft-lock derivation on {@link com.minecart.logic.CircuitComponent}
 * and {@link com.minecart.logic.CircuitEdge} (computed from constituent node lock counts). The two are
 * combined via {@link #and(LockMode, LockMode)} to give the effective mode that drag / panel / gesture
 * code consults before applying any change.
 *
 * <h2>Operation semantics</h2>
 * <ul>
 *   <li>{@link #FREE} — both translation and rotation permitted.</li>
 *   <li>{@link #POSITION_FREE} — translation permitted, rotation locked. For point-like elements
 *       (nodes), behaves the same as {@link #FREE}; nodes have no rotation to constrain.</li>
 *   <li>{@link #ROTATION_FREE} — rotation around a stored pivot permitted, translation locked. For
 *       point-like elements (nodes), behaves the same as {@link #LOCKED}; a node's rotation is
 *       degenerate.</li>
 *   <li>{@link #LOCKED} — neither translation nor rotation permitted.</li>
 * </ul>
 *
 * <h2>Why {@link #and(LockMode, LockMode) AND}</h2>
 * Each mode is conceptually the SET of motion operations it permits. Combining two constraints (e.g.
 * strict + soft) requires both to allow an operation, so the result is the intersection:
 * <pre>
 *   FREE          ∩ X             = X
 *   POSITION_FREE ∩ ROTATION_FREE = LOCKED   (disjoint operation sets)
 *   POSITION_FREE ∩ POSITION_FREE = POSITION_FREE
 *   ROTATION_FREE ∩ ROTATION_FREE = ROTATION_FREE   (caller must also reconcile pivots)
 *   LOCKED        ∩ X             = LOCKED
 * </pre>
 */
public enum LockMode {
    FREE,
    POSITION_FREE,
    ROTATION_FREE,
    LOCKED;

    /**
     * Intersection of two lock modes — the set of motions both inputs permit.
     *
     * <p>Pivot reconciliation for the {@code ROTATION_FREE ∩ ROTATION_FREE} case is intentionally NOT
     * handled here (this enum has no idea about pivot positions). Callers that combine two
     * rotation-free states must compare pivots externally and downgrade to {@link #LOCKED} when the
     * pivots disagree — see {@link com.minecart.logic.CircuitComponent#effectiveLockMode()} (when
     * Phase 2b lands the per-element wiring).
     */
    public static LockMode and(LockMode a, LockMode b) {
        if (a == null || b == null) {
            return LOCKED;
        }
        if (a == LOCKED || b == LOCKED) {
            return LOCKED;
        }
        if (a == FREE) {
            return b;
        }
        if (b == FREE) {
            return a;
        }
        if (a == b) {
            return a;
        }
        // POSITION_FREE ∩ ROTATION_FREE (in either order) — operation sets don't overlap.
        return LOCKED;
    }

    /**
     * Collapses this mode to the semantics meaningful for a point-like element (a {@link
     * com.minecart.logic.CircuitNode}) which has no rotational degree of freedom: rotation
     * constraints have no bite, so {@link #POSITION_FREE} folds back to {@link #FREE} and
     * {@link #ROTATION_FREE} folds to {@link #LOCKED} (rotation-only freedom on a 0-DOF rotation
     * leaves no motion at all).
     */
    public LockMode forNode() {
        return switch (this) {
            case POSITION_FREE -> FREE;
            case ROTATION_FREE -> LOCKED;
            default -> this;
        };
    }

    /** Convenience: does this mode permit pure translation? */
    public boolean allowsTranslation() {
        return this == FREE || this == POSITION_FREE;
    }

    /** Convenience: does this mode permit rotation? */
    public boolean allowsRotation() {
        return this == FREE || this == ROTATION_FREE;
    }
}
