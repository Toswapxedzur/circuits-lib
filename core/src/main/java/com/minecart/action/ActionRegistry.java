package com.minecart.action;

import com.minecart.serialization.tag.CompoundTag;

import java.util.HashMap;
import java.util.Map;

/**
 * Central registry for {@link Action} kinds. Each {@link ActionType} supplies a no-arg factory and uses {@link Action#load(CompoundTag)} to decode.
 */
public final class ActionRegistry {

    protected static final Map<String, ActionType<?>> TYPES = new HashMap<>();
    protected static boolean frozen;

    protected ActionRegistry() {
    }

    /**
     * Registers an action kind. The type's id must be unique; it matches {@link ActionType#getId()}.
     */
    public static void register(ActionType<?> type) {
        if (frozen) {
            throw new UnsupportedOperationException("Action registry is frozen");
        }
        String id = type.getId();
        if (TYPES.containsKey(id)) {
            throw new IllegalArgumentException("Duplicate action registry id: " + id);
        }
        TYPES.put(id, type);
    }

    public static void freeze() {
        frozen = true;
    }

    public static boolean isFrozen() {
        return frozen;
    }
}
