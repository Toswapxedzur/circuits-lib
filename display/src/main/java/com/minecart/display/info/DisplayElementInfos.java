package com.minecart.display.info;

import com.minecart.registry.ElementInfoRegistry;
import com.minecart.registry.ElementInfoType;

/**
 * Registry constants for {@link com.minecart.variant.ElementInfo} types contributed by the display module.
 * Mirrors {@link com.minecart.registry.AllComponents} for {@link com.minecart.registry.CircuitElementType}.
 *
 * <p>Class init touches the registry; reference {@code DisplayElementInfos.POSITION} (or
 * {@code Class.forName("com.minecart.display.info.DisplayElementInfos")}) once during display startup
 * to ensure the types are registered before any circuit is loaded.
 */
public class DisplayElementInfos {

    public static final ElementInfoType<PositionInfo> POSITION =
            ElementInfoRegistry.register("display:position", PositionInfo::new);

    private DisplayElementInfos() {}
}
