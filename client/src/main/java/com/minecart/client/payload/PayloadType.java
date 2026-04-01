package com.minecart.client.payload;

import java.util.function.Supplier;

/**
 * Describes a registered payload kind: stable string id and a factory for empty instances used with
 * {@link Payload#load(com.minecart.serialization.tag.CompoundTag)}.
 *
 * @param <T> concrete {@link Payload} subclass
 */
public final class PayloadType<T extends Payload> {

    private final String id;
    private final Supplier<T> factory;

    PayloadType(String id, Supplier<T> factory) {
        this.id = id;
        this.factory = factory;
    }

    /** Registry string id (same as {@link Payload#getPayloadId()}). */
    public String getId() {
        return id;
    }

    /** New instance for deserialization; call {@link Payload#load} on it. */
    public T create() {
        return factory.get();
    }
}
