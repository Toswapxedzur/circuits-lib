package com.minecart.client.payload.server;

import com.minecart.client.misc.ClientStrings;
import com.minecart.client.payload.Payload;
import com.minecart.client.payload.PayloadRegistry;
import com.minecart.client.payload.PayloadType;
import com.minecart.client.handler.server.CircuitElementHandler;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;
import com.minecart.serialization.tag.ListTag;
import com.minecart.serialization.tag.Tag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Server → client circuit element deltas: ordered {@link CircuitElementChange} steps (insert, remove, sync change).
 * Serialization and binary framing are defined here; client application is {@link CircuitElementHandler}.
 */
public final class CircuitElementPayload implements Payload {

    public static final String PAYLOAD_ID = ClientStrings.PAYLOAD_CIRCUIT_ELEMENT;

    public static final PayloadType<CircuitElementPayload> TYPE =
            PayloadRegistry.register(PAYLOAD_ID, CircuitElementPayload::new);

    private UUID worldId;
    private UUID circuitId;
    private final List<CircuitElementChange> changes = new ArrayList<>();

    public CircuitElementPayload() {
    }

    public CircuitElementPayload(UUID worldId, UUID circuitId, List<CircuitElementChange> changes) {
        this.worldId = worldId;
        this.circuitId = circuitId;
        if (changes != null) {
            this.changes.addAll(changes);
        }
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

    public List<CircuitElementChange> getChanges() {
        return Collections.unmodifiableList(changes);
    }

    @Override
    public void save(CompoundTag tag) {
        Payload.super.save(tag);
        TagUtil.putUUID(tag, ClientStrings.TAG_WORLD_ID, worldId);
        TagUtil.putUUID(tag, ClientStrings.TAG_CIRCUIT_ID, circuitId);
        ListTag list = new ListTag();
        for (CircuitElementChange c : changes) {
            CompoundTag step = new CompoundTag();
            c.save(step);
            list.add(step);
        }
        tag.put(ClientStrings.TAG_CHANGES, list);
    }

    @Override
    public void load(CompoundTag tag) {
        Payload.super.load(tag);
        worldId = TagUtil.getUUID(tag, ClientStrings.TAG_WORLD_ID);
        circuitId = TagUtil.getUUID(tag, ClientStrings.TAG_CIRCUIT_ID);
        if (circuitId == null) {
            throw new IllegalArgumentException("Missing '" + ClientStrings.TAG_CIRCUIT_ID + "'");
        }
        changes.clear();
        Tag t = tag.get(ClientStrings.TAG_CHANGES);
        if (t instanceof ListTag list) {
            for (int i = 0; i < list.size(); i++) {
                CompoundTag step = TagUtil.requireCompoundTag(list.get(i), ClientStrings.TAG_CHANGES + "[" + i + "]");
                changes.add(CircuitElementChange.load(step));
            }
        }
    }
}
