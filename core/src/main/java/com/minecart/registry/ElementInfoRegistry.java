package com.minecart.registry;

import com.minecart.variant.ElementInfo;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Registry of {@link ElementInfoType}s, keyed by stable string id. Mirrors {@link CircuitElementRegistry}
 * but for the {@link ElementInfo} variant attached to elements via
 * {@link com.minecart.event.events.ElementInfoInjectEvent}.
 *
 * <p>Unknown ids encountered during load are silently skipped (the contributing module may not be loaded).
 */
public class ElementInfoRegistry {

    protected static final Map<String, ElementInfoType<?>> REGISTRY = new HashMap<>();
    protected static boolean isFrozen = false;

    public static <I extends ElementInfo> ElementInfoType<I> register(String id, Supplier<I> factory) {
        if (isFrozen) {
            throw new UnsupportedOperationException(
                    "ElementInfoRegistry is already frozen and no further info type can be registered");
        }
        if (REGISTRY.containsKey(id)) {
            throw new IllegalArgumentException("ElementInfo id already registered: " + id);
        }
        ElementInfoType<I> type = new ElementInfoType<>(id, factory);
        REGISTRY.put(id, type);
        return type;
    }

    /** @return the registered type for {@code id}, or {@code null} if no module has registered it. */
    public static ElementInfoType<?> getType(String id) {
        return REGISTRY.get(id);
    }

    public static void freeze() {
        isFrozen = true;
    }

    public static boolean isFrozen() {
        return isFrozen;
    }
}
