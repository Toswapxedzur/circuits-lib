package com.minecart.client.payload.server.lifecycle;

import com.minecart.client.ClientStrings;
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

    public static final String PAYLOAD_ID = ClientStrings.PAYLOAD_CIRCUIT_LIFECYCLE;

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
        TagUtil.putUUID(tag, ClientStrings.TAG_WORLD_ID, worldId);
        TagUtil.putUUID(tag, ClientStrings.TAG_CIRCUIT_ID, circuitId);
        if (kind != null) {
            tag.putString(ClientStrings.TAG_KIND, kind == Kind.INSERT ? ClientStrings.KIND_INSERT : ClientStrings.KIND_REMOVE);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        Payload.super.load(tag);
        worldId = TagUtil.getUUID(tag, ClientStrings.TAG_WORLD_ID);
        circuitId = TagUtil.getUUID(tag, ClientStrings.TAG_CIRCUIT_ID);
        if (worldId == null) {
            throw new IllegalArgumentException("Missing '" + ClientStrings.TAG_WORLD_ID + "'");
        }
        if (circuitId == null) {
            throw new IllegalArgumentException("Missing '" + ClientStrings.TAG_CIRCUIT_ID + "'");
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
