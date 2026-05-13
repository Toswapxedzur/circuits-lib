package com.minecart.protocol.payload.client;

import com.minecart.protocol.misc.ProtocolStrings;
import com.minecart.protocol.payload.Payload;
import com.minecart.protocol.payload.PayloadRegistry;
import com.minecart.protocol.payload.PayloadType;
import com.minecart.serialization.tag.CompoundTag;

/**
 * Client → server: "create a fresh world with the given display {@link #getName() name}". The server picks a
 * fresh {@link java.util.UUID}, stamps the name, and broadcasts a
 * {@link com.minecart.protocol.payload.server.WorldLifecyclePayload#insert(java.util.UUID, String)} so every
 * connected client mirror picks up the new world.
 */
public final class CreateWorldPayload implements Payload {

    public static final String PAYLOAD_ID = ProtocolStrings.PAYLOAD_CREATE_WORLD;

    public static final PayloadType<CreateWorldPayload> TYPE =
            PayloadRegistry.register(PAYLOAD_ID, CreateWorldPayload::new);

    private String name;

    public CreateWorldPayload() {
    }

    public CreateWorldPayload(String name) {
        this.name = name;
    }

    @Override
    public String getPayloadId() {
        return PAYLOAD_ID;
    }

    @Override
    public Destination getDestination() {
        return Destination.SERVER;
    }

    public String getName() {
        return name;
    }

    @Override
    public void save(CompoundTag tag) {
        Payload.super.save(tag);
        if (name != null) {
            tag.putString(ProtocolStrings.TAG_WORLD_NAME, name);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        Payload.super.load(tag);
        String n = tag.getString(ProtocolStrings.TAG_WORLD_NAME);
        name = (n == null || n.isEmpty()) ? null : n;
    }
}
