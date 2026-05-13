package com.minecart.protocol.payload.client;

import com.minecart.protocol.misc.ProtocolStrings;
import com.minecart.protocol.payload.Payload;
import com.minecart.protocol.payload.PayloadRegistry;
import com.minecart.protocol.payload.PayloadType;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;

import java.util.UUID;

/**
 * Client → server: "rename the world with this id to {@link #getName() name}". On success the server
 * broadcasts {@link com.minecart.protocol.payload.server.WorldLifecyclePayload#rename(UUID, String)} so every
 * client mirror updates the displayed name. No-op on the server if {@code worldId} is unknown.
 */
public final class RenameWorldPayload implements Payload {

    public static final String PAYLOAD_ID = ProtocolStrings.PAYLOAD_RENAME_WORLD;

    public static final PayloadType<RenameWorldPayload> TYPE =
            PayloadRegistry.register(PAYLOAD_ID, RenameWorldPayload::new);

    private UUID worldId;
    private String name;

    public RenameWorldPayload() {
    }

    public RenameWorldPayload(UUID worldId, String name) {
        this.worldId = worldId;
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

    public UUID getWorldId() {
        return worldId;
    }

    public String getName() {
        return name;
    }

    @Override
    public void save(CompoundTag tag) {
        Payload.super.save(tag);
        TagUtil.putUUID(tag, ProtocolStrings.TAG_WORLD_ID, worldId);
        if (name != null) {
            tag.putString(ProtocolStrings.TAG_WORLD_NAME, name);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        Payload.super.load(tag);
        worldId = TagUtil.getUUID(tag, ProtocolStrings.TAG_WORLD_ID);
        if (worldId == null) {
            throw new IllegalArgumentException("Missing '" + ProtocolStrings.TAG_WORLD_ID + "'");
        }
        String n = tag.getString(ProtocolStrings.TAG_WORLD_NAME);
        name = (n == null || n.isEmpty()) ? null : n;
    }
}
