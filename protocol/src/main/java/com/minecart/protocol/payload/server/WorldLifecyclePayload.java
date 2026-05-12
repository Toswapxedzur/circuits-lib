package com.minecart.protocol.payload.server;

import com.minecart.foundation.World;
import com.minecart.protocol.misc.ProtocolStrings;
import com.minecart.protocol.payload.Payload;
import com.minecart.protocol.payload.PayloadRegistry;
import com.minecart.protocol.payload.PayloadType;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;

import java.util.UUID;

/**
 * Server → client: adding or removing a {@link World} on the level. Client-side application lives in the client module.
 */
public final class WorldLifecyclePayload implements Payload {

    public static final String PAYLOAD_ID = ProtocolStrings.PAYLOAD_WORLD_LIFECYCLE;

    public static final PayloadType<WorldLifecyclePayload> TYPE =
            PayloadRegistry.register(PAYLOAD_ID, WorldLifecyclePayload::new);

    public enum Kind {
        INSERT,
        REMOVE
    }

    private UUID worldId;
    private Kind kind;

    public WorldLifecyclePayload() {
    }

    public WorldLifecyclePayload(UUID worldId, Kind kind) {
        this.worldId = worldId;
        this.kind = kind;
    }

    public static WorldLifecyclePayload insert(UUID worldId) {
        return new WorldLifecyclePayload(worldId, Kind.INSERT);
    }

    public static WorldLifecyclePayload remove(UUID worldId) {
        return new WorldLifecyclePayload(worldId, Kind.REMOVE);
    }

    @Override
    public String getPayloadId() {
        return PAYLOAD_ID;
    }

    @Override
    public Destination getDestination() {
        return Destination.CLIENT;
    }

    public UUID getWorldId() {
        return worldId;
    }

    public void setWorldId(UUID worldId) {
        this.worldId = worldId;
    }

    public Kind getKind() {
        return kind;
    }

    public void setKind(Kind kind) {
        this.kind = kind;
    }

    @Override
    public void save(CompoundTag tag) {
        Payload.super.save(tag);
        TagUtil.putUUID(tag, ProtocolStrings.TAG_WORLD_ID, worldId);
        if (kind != null) {
            tag.putString(ProtocolStrings.TAG_KIND, kind == Kind.INSERT ? ProtocolStrings.KIND_INSERT : ProtocolStrings.KIND_REMOVE);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        Payload.super.load(tag);
        worldId = TagUtil.getUUID(tag, ProtocolStrings.TAG_WORLD_ID);
        if (worldId == null) {
            throw new IllegalArgumentException("Missing '" + ProtocolStrings.TAG_WORLD_ID + "'");
        }
        String k = tag.getString(ProtocolStrings.TAG_KIND);
        if (ProtocolStrings.KIND_INSERT.equalsIgnoreCase(k)) {
            kind = Kind.INSERT;
        } else if (ProtocolStrings.KIND_REMOVE.equalsIgnoreCase(k)) {
            kind = Kind.REMOVE;
        } else {
            throw new IllegalArgumentException("Missing or invalid '" + ProtocolStrings.TAG_KIND + "'");
        }
    }
}
