package com.minecart.client.payload;

import com.minecart.serialization.tag.CompoundTag;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Central registry for {@link Payload} kinds, parallel to {@link com.minecart.registry.CircuitElementRegistry}.
 * Each kind registers a stable id and a no-arg factory; use {@link #deserialize(CompoundTag)} to decode by
 * {@link Payload#TAG_PAYLOAD_ID}.
 */
public final class PayloadRegistry {

    protected static final Map<String, PayloadType<?>> REGISTRY = new HashMap<>();
    protected static boolean isFrozen = false;

    private PayloadRegistry() {
    }

    /**
     * Registers a payload kind. {@code id} must equal {@code factory.get().getPayloadId()} for the empty instance.
     *
     * @return the registered type handle (same pattern as {@link com.minecart.registry.CircuitElementRegistry#register})
     */
    public static <T extends Payload> PayloadType<T> register(String id, Supplier<T> factory) {
        if (isFrozen) {
            throw new UnsupportedOperationException("The payload registry is already frozen and no further payload can be registered");
        }
        if (REGISTRY.containsKey(id)) {
            throw new IllegalArgumentException("Payload ID already registered: " + id);
        }
        T probe = factory.get();
        if (!id.equals(probe.getPayloadId())) {
            throw new IllegalArgumentException(
                    "Payload id mismatch: registered " + id + " but empty instance reports " + probe.getPayloadId());
        }
        PayloadType<T> type = new PayloadType<>(id, factory);
        REGISTRY.put(id, type);
        return type;
    }

    public static PayloadType<?> getType(String id) {
        PayloadType<?> type = REGISTRY.get(id);
        if (type == null) {
            throw new IllegalArgumentException("Unknown payload ID: " + id);
        }
        return type;
    }

    /**
     * Call after registration phase is complete to forbid further {@link #register} calls.
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
