package com.minecart.variant;

import com.minecart.serialization.TagSerializable;

/**
 * A piece of arbitrary metadata attachable to a {@link com.minecart.logic.CircuitElement}, keyed by
 * {@link com.minecart.registry.ElementInfoType}. Inspired by the Minecraft data-component system: at most
 * one info instance per type per element. Modules outside {@code core} (e.g. {@code display} for positions)
 * can attach their own info via {@link com.minecart.event.events.ElementInfoInjectEvent}.
 *
 * <p>Implementations are {@link TagSerializable}. Whether an instance actually gets written to disk during
 * {@link com.minecart.foundation.Circuit#save} is controlled per-instance by {@link #shouldSerialize()};
 * transient/derived infos can return {@code false} to be skipped.
 */
public interface ElementInfo extends TagSerializable {

    /**
     * @return {@code true} if this info should be persisted by {@link com.minecart.logic.CircuitElement#save}.
     *         Default {@code true}; override and return {@code false} for transient/derived data.
     */
    default boolean shouldSerialize() {
        return true;
    }
}
