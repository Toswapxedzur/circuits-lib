package com.minecart.protocol.payload.server;

import com.minecart.protocol.misc.ProtocolStrings;
import com.minecart.protocol.payload.Payload;
import com.minecart.protocol.payload.PayloadRegistry;
import com.minecart.protocol.payload.PayloadType;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;

import java.util.UUID;

/**
 * Server → client: adding or removing an empty circuit in a world. Application handlers live in the client / server modules.
 */
public final class CircuitLifecyclePayload implements Payload {

    public static final String PAYLOAD_ID = ProtocolStrings.PAYLOAD_CIRCUIT_LIFECYCLE;

    public static final PayloadType<CircuitLifecyclePayload> TYPE =
            PayloadRegistry.register(PAYLOAD_ID, CircuitLifecyclePayload::new);

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
        Payload.super.save(tag);
        TagUtil.putUUID(tag, ProtocolStrings.TAG_WORLD_ID, worldId);
        TagUtil.putUUID(tag, ProtocolStrings.TAG_CIRCUIT_ID, circuitId);
        if (kind != null) {
            tag.putString(ProtocolStrings.TAG_KIND, kind == Kind.INSERT ? ProtocolStrings.KIND_INSERT : ProtocolStrings.KIND_REMOVE);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        Payload.super.load(tag);
        worldId = TagUtil.getUUID(tag, ProtocolStrings.TAG_WORLD_ID);
        circuitId = TagUtil.getUUID(tag, ProtocolStrings.TAG_CIRCUIT_ID);
        if (worldId == null) {
            throw new IllegalArgumentException("Missing '" + ProtocolStrings.TAG_WORLD_ID + "'");
        }
        if (circuitId == null) {
            throw new IllegalArgumentException("Missing '" + ProtocolStrings.TAG_CIRCUIT_ID + "'");
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
