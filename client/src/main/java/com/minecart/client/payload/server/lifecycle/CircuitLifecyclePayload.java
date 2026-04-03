package com.minecart.client.payload.server.lifecycle;

import com.minecart.client.payload.Payload;
import com.minecart.client.payload.PayloadRegistry;
import com.minecart.client.payload.PayloadType;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;

import java.util.UUID;

/**
 * Server → client: adding or removing an empty circuit in a world. Application: {@link CircuitLifecycleHandler}.
 */
public final class CircuitLifecyclePayload implements Payload {

    public static final String PAYLOAD_ID = "minecart.circuit_lifecycle_payload";

    public static final PayloadType<CircuitLifecyclePayload> TYPE =
            PayloadRegistry.register(PAYLOAD_ID, CircuitLifecyclePayload::new);

    private static final String TAG_WORLD_ID = "world_id";
    private static final String TAG_CIRCUIT_ID = "circuit_id";
    private static final String TAG_KIND = "kind";

    public enum Kind {
        INSERT,
        REMOVE
    }

    private UUID worldId;
    private UUID circuitId;
    private Kind kind;

    public CircuitLifecyclePayload() {
    }

    public CircuitLifecyclePayload(UUID worldId, UUID circuitId, Kind kind) {
        this.worldId = worldId;
        this.circuitId = circuitId;
        this.kind = kind;
    }

    public static CircuitLifecyclePayload insert(UUID worldId, UUID circuitId) {
        return new CircuitLifecyclePayload(worldId, circuitId, Kind.INSERT);
    }

    public static CircuitLifecyclePayload remove(UUID worldId, UUID circuitId) {
        return new CircuitLifecyclePayload(worldId, circuitId, Kind.REMOVE);
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

    public UUID getCircuitId() {
        return circuitId;
    }

    public void setCircuitId(UUID circuitId) {
        this.circuitId = circuitId;
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
        TagUtil.putUUID(tag, TAG_CIRCUIT_ID, circuitId);
        if (kind != null) {
            tag.putString(TAG_KIND, kind == Kind.INSERT ? "insert" : "remove");
        }
    }

    @Override
    public void load(CompoundTag tag) {
        Payload.verifyEnvelope(tag, getPayloadId());
        worldId = TagUtil.getUUID(tag, TAG_WORLD_ID);
        circuitId = TagUtil.getUUID(tag, TAG_CIRCUIT_ID);
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
