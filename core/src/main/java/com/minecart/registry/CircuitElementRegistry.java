package com.minecart.registry;

import com.minecart.logic.CircuitElement;
import com.minecart.logic.World;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public class CircuitElementRegistry {
    protected static final Map<String, CircuitElementType> REGISTRY = new HashMap<>();
    protected static boolean isFrozen = false;

    public static <T extends CircuitElement> CircuitElementType<T> register(String id, Function<World, T> factory) {
        if(isFrozen){
            throw new UnsupportedOperationException("The registry is already frozen and no further component can be registered");
        }
        if (REGISTRY.containsKey(id)) {
            throw new IllegalArgumentException("CircuitElement ID already registered: " + id);
        }
        CircuitElementType<T> type = new CircuitElementType<>(id, factory);
        REGISTRY.put(id, type);
        return type;
    }

    public static CircuitElementType<?> getType(String id) {
        CircuitElementType<?> type = REGISTRY.get(id);
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
