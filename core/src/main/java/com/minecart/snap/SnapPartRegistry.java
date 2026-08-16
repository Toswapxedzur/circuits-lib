package com.minecart.snap;

import java.util.HashMap;
import java.util.Map;

/**
 * Registry of {@link SnapPartType}s keyed by stable id, mirroring
 * {@link com.minecart.registry.CircuitElementRegistry}. The built-in parts live in {@link AllSnapParts};
 * additional parts (or future user-defined ones) register here the same way, which is what makes the
 * snap-part palette extensible.
 */
public final class SnapPartRegistry {

    private static final Map<String, SnapPartType> REGISTRY = new HashMap<>();

    private SnapPartRegistry() {}

    public static SnapPartType register(String id, int height, int length, boolean connector,
                                        SnapPartType.Builder builder) {
        if (REGISTRY.containsKey(id)) {
            throw new IllegalArgumentException("Snap part id already registered: " + id);
        }
        SnapPartType type = new SnapPartType(id, height, length, connector, builder);
        REGISTRY.put(id, type);
        return type;
    }

    /** @return the registered part for {@code id}, or {@code null} if none. */
    public static SnapPartType getType(String id) {
        return REGISTRY.get(id);
    }
}
