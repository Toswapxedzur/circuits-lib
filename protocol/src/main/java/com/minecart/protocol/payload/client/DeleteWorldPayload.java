package com.minecart.protocol.payload.client;

import com.minecart.protocol.misc.ProtocolStrings;
import com.minecart.protocol.payload.Payload;
import com.minecart.protocol.payload.PayloadRegistry;
import com.minecart.protocol.payload.PayloadType;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;

import java.util.UUID;

/**
 * Client → server: "destroy the world with this id". On success the server broadcasts
 * {@link com.minecart.protocol.payload.server.WorldLifecyclePayload#remove(UUID)} so every client mirror
 * drops the world. No-op on the server if {@code worldId} is unknown.
 */
public final class DeleteWorldPayload implements Payload {

    public static final String PAYLOAD_ID = ProtocolStrings.PAYLOAD_DELETE_WORLD;

    public static final PayloadType<DeleteWorldPayload> TYPE =
            PayloadRegistry.register(PAYLOAD_ID, DeleteWorldPayload::new);

    private UUID worldId;

    public DeleteWorldPayload() {
    }

    public DeleteWorldPayload(UUID worldId) {
        this.worldId = worldId;
    }

    @Override
    public String getPayloadId() {
        return PAYLOAD_ID;
    }

    @Override
    public Destination getDestination() {
        return Destination.SERVER;
    }

    public UUID getWorldId() {
        return worldId;
    }

    @Override
    public void save(CompoundTag tag) {
        Payload.super.save(tag);
        TagUtil.putUUID(tag, ProtocolStrings.TAG_WORLD_ID, worldId);
    }

    @Override
    public void load(CompoundTag tag) {
        Payload.super.load(tag);
        worldId = TagUtil.getUUID(tag, ProtocolStrings.TAG_WORLD_ID);
        if (worldId == null) {
            throw new IllegalArgumentException("Missing '" + ProtocolStrings.TAG_WORLD_ID + "'");
        }
    }
}
