package com.minecart.registry;

import com.minecart.component.CircuitNode;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public class ComponentRegistry {
    protected static final Map<String, ComponentType> REGISTRY = new HashMap<>();
    protected static boolean isFrozen = false;

    public static <T extends CircuitNode> ComponentType<T> register(String id, Supplier<T> factory) {
        if(isFrozen){
            throw new UnsupportedOperationException("The registry is already frozen and no further component can be registered");
        }
        if (REGISTRY.containsKey(id)) {
            throw new IllegalArgumentException("Component ID already registered: " + id);
        }
        ComponentType<T> type = new ComponentType<>(id, factory);
        REGISTRY.put(id, type);
        return type;
    }

    public static ComponentType<?> getType(String id) {
        ComponentType<?> type = REGISTRY.get(id);
        if (type == null) {
            throw new IllegalArgumentException("Unknown component ID: " + id);
        }
        return type;
    }

    /**
     * Call this method after your program's registration phase is complete.
     * Once called, no further components can be added to the circuit engine.
     * However, it is not required as it allows registering component after loading phase completed.
     */
    public static void freeze() {
        if (isFrozen) {
            return;
        }
        isFrozen = true;
    }

    public static boolean isFrozen() {
        return isFrozen;
    }
}
