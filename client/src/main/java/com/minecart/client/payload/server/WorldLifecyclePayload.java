package com.minecart.client.payload.server;

import com.minecart.client.misc.ClientStrings;
import com.minecart.client.payload.Payload;
import com.minecart.client.payload.PayloadRegistry;
import com.minecart.client.payload.PayloadType;
import com.minecart.client.handler.server.WorldLifecycleHandler;
import com.minecart.foundation.World;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;

import java.util.UUID;

/**
 * Server → client: adding or removing a {@link World} on the level. Application: {@link WorldLifecycleHandler}.
 */
public final class WorldLifecyclePayload implements Payload {

    public static final String PAYLOAD_ID = ClientStrings.PAYLOAD_WORLD_LIFECYCLE;

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
        TagUtil.putUUID(tag, ClientStrings.TAG_WORLD_ID, worldId);
        if (kind != null) {
            tag.putString(ClientStrings.TAG_KIND, kind == Kind.INSERT ? ClientStrings.KIND_INSERT : ClientStrings.KIND_REMOVE);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        Payload.super.load(tag);
        worldId = TagUtil.getUUID(tag, ClientStrings.TAG_WORLD_ID);
        if (worldId == null) {
            throw new IllegalArgumentException("Missing '" + ClientStrings.TAG_WORLD_ID + "'");
        }
        String k = tag.getString(ClientStrings.TAG_KIND);
        if (ClientStrings.KIND_INSERT.equalsIgnoreCase(k)) {
            kind = Kind.INSERT;
        } else if (ClientStrings.KIND_REMOVE.equalsIgnoreCase(k)) {
            kind = Kind.REMOVE;
        } else {
            throw new IllegalArgumentException("Missing or invalid '" + ClientStrings.TAG_KIND + "'");
        }
    }
}
