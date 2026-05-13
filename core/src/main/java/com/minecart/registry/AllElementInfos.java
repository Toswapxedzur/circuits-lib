package com.minecart.registry;

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

    private AllElementInfos() {}
}
