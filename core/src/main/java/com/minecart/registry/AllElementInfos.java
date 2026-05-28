package com.minecart.registry;

import com.minecart.variant.info.LockInfo;
import com.minecart.variant.info.PositionInfo;
import com.minecart.variant.info.RotationInfo;

/**
 * Built-in {@link ElementInfoType}s shipped with {@code :core}. Mirrors {@link AllComponents} for circuit elements
 * and {@code AllPayloads} for protocol payloads. Touch this class once at startup to ensure these types are
 * registered before any circuit is loaded.
 */
public final class AllElementInfos {

    public static final ElementInfoType<PositionInfo> POSITION =
            ElementInfoRegistry.register("core:position", PositionInfo::new);

    public static final ElementInfoType<RotationInfo> ROTATION =
            ElementInfoRegistry.register("core:rotation", RotationInfo::new);

    /**
     * Player-authored "strict" lock state. Components and edges carry this from their placement
     * handlers; nodes don't (they reuse {@link PositionInfo#isFixed()} as a 2-phase lock since
     * rotation modes are degenerate on a point). Soft lock is recomputed at call time from
     * constituent {@link PositionInfo}s, so it lives in code, not as a stored info.
     */
    public static final ElementInfoType<LockInfo> LOCK =
            ElementInfoRegistry.register("core:lock", LockInfo::new);

    private AllElementInfos() {}

    /**
     * Force this class's {@code <clinit>} to run. Same rationale as
     * {@link AllComponents#init()} — the {@link ElementInfoRegistry#register(String, java.util.function.Supplier)}
     * calls above happen as a side-effect of initialization, so something has to actually
     * <em>invoke</em> a member of this class (not just reference {@code AllElementInfos.class}) to
     * make them happen. Per JLS §12.4.1, invoking a static method qualifies; a class literal does
     * not.
     */
    public static void init() {
        // Intentionally empty. See javadoc.
    }
}
