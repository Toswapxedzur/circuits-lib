package com.minecart.client.payload.server.lifecycle;

import com.minecart.client.payload.Payload;
import com.minecart.client.payload.PayloadRegistry;
import com.minecart.client.payload.PayloadType;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;

import java.util.UUID;

/**
 * Server → client: adding or removing a {@link com.minecart.logic.World} on the level. Application: {@link WorldLifecycleHandler}.
 */
public final class WorldLifecyclePayload implements Payload {

    public static final String PAYLOAD_ID = "minecart.world_lifecycle_payload";

    public static final PayloadType<WorldLifecyclePayload> TYPE =
            PayloadRegistry.register(PAYLOAD_ID, WorldLifecyclePayload::new);

    private static final String TAG_WORLD_ID = "world_id";
    private static final String TAG_KIND = "kind";

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
        Payload.writeEnvelope(tag, this);
        TagUtil.putUUID(tag, TAG_WORLD_ID, worldId);
        if (kind != null) {
            tag.putString(TAG_KIND, kind == Kind.INSERT ? "insert" : "remove");
        }
    }

    @Override
    public void load(CompoundTag tag) {
        Payload.verifyEnvelope(tag, getPayloadId());
        worldId = TagUtil.getUUID(tag, TAG_WORLD_ID);
        String k = tag.getString(TAG_KIND);
        if ("insert".equalsIgnoreCase(k)) {
            kind = Kind.INSERT;
        } else if ("remove".equalsIgnoreCase(k)) {
            kind = Kind.REMOVE;
        } else {
            throw new IllegalArgumentException("Missing or invalid '" + TAG_KIND + "'");
        }
    }
}
