package com.minecart.protocol.payload;

import com.minecart.serialization.tag.CompoundTag;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Central registry for {@link Payload} kinds, parallel to {@link com.minecart.registry.CircuitElementRegistry}.
 * Each kind registers a stable id and a no-arg factory; use {@link #deserialize(CompoundTag)} to decode by
 * {@link Payload#TAG_PAYLOAD_ID}.
 */
public final class PayloadRegistry {

    // ConcurrentHashMap: entries are inserted lazily by each payload class's <clinit> (on whatever
    // application thread first touches the class), then read by Netty IO threads during decode.
    // A plain HashMap gives no happens-before between those writes and cross-thread reads, so a
    // concurrent resize could drop or corrupt keys; ConcurrentHashMap publishes each put safely.
    protected static final Map<String, PayloadType<?>> REGISTRY = new ConcurrentHashMap<>();

    private PayloadRegistry() {
    }

    /**
     * Registers a payload kind. {@code id} must equal {@code factory.get().getPayloadId()} for the empty instance.
     *
     * @return the registered type handle (same pattern as {@link com.minecart.registry.CircuitElementRegistry#register})
     */
    public static <T extends Payload> PayloadType<T> register(String id, Supplier<T> factory) {
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
}
