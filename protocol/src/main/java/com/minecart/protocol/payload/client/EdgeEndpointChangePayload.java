package com.minecart.protocol.payload.client;

import com.minecart.protocol.misc.ProtocolStrings;
import com.minecart.protocol.payload.Payload;
import com.minecart.protocol.payload.PayloadRegistry;
import com.minecart.protocol.payload.PayloadType;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;

import java.util.UUID;

/**
 * Client → server: "repoint the edge {@code edgeId} inside {@code worldId} so its endpoints become
 * {@code newStartNodeId} / {@code newEndNodeId}". Routes through
 * {@link com.minecart.logic.ServerWorld#changeEdgeEndpoint} which handles connection-set bookkeeping,
 * circuit merge/split, and replication. Either id may match the edge's current endpoint to mean
 * "leave that side alone"; passing both as the current values is a server-side no-op.
 *
 * <p>This is one of the granular building blocks the editor's node-combine flow assembles instead of
 * sending a composite "combine" payload — keeping the protocol a flat sequence of atomic edits gives
 * future tooling (scripted refactors, multi-step undo) full control over how a topology change is
 * decomposed without the server inventing surprise side effects.
 *
 * <p>Silent no-op on unknown world / edge / endpoint nodes, or when the requested change would create
 * a self-loop; the server responds by not emitting any element-change deltas, which the client
 * implicitly understands as "rejected".
 */
public final class EdgeEndpointChangePayload implements Payload {

    public static final String PAYLOAD_ID = ProtocolStrings.PAYLOAD_EDGE_ENDPOINT_CHANGE;

    public static final PayloadType<EdgeEndpointChangePayload> TYPE =
            PayloadRegistry.register(PAYLOAD_ID, EdgeEndpointChangePayload::new);

    private UUID worldId;
    private UUID edgeId;
    private UUID newStartNodeId;
    private UUID newEndNodeId;

    public EdgeEndpointChangePayload() {
    }

    public EdgeEndpointChangePayload(UUID worldId, UUID edgeId, UUID newStartNodeId, UUID newEndNodeId) {
        this.worldId = worldId;
        this.edgeId = edgeId;
        this.newStartNodeId = newStartNodeId;
        this.newEndNodeId = newEndNodeId;
    }

    @Override
    public String getPayloadId() {
        return PAYLOAD_ID;
    }

    @Override
    public Destination getDestination() {
        return Destination.SERVER;
    }

    public UUID getWorldId() { return worldId; }
    public UUID getEdgeId() { return edgeId; }
    public UUID getNewStartNodeId() { return newStartNodeId; }
    public UUID getNewEndNodeId() { return newEndNodeId; }

    @Override
    public void save(CompoundTag tag) {
        Payload.super.save(tag);
        TagUtil.putUUID(tag, ProtocolStrings.TAG_WORLD_ID, worldId);
        TagUtil.putUUID(tag, ProtocolStrings.TAG_EDGE_ID, edgeId);
        TagUtil.putUUID(tag, ProtocolStrings.TAG_NEW_START_NODE_ID, newStartNodeId);
        TagUtil.putUUID(tag, ProtocolStrings.TAG_NEW_END_NODE_ID, newEndNodeId);
    }

    @Override
    public void load(CompoundTag tag) {
        Payload.super.load(tag);
        worldId = TagUtil.getUUID(tag, ProtocolStrings.TAG_WORLD_ID);
        if (worldId == null) {
            throw new IllegalArgumentException("Missing '" + ProtocolStrings.TAG_WORLD_ID + "'");
        }
        edgeId = TagUtil.getUUID(tag, ProtocolStrings.TAG_EDGE_ID);
        if (edgeId == null) {
            throw new IllegalArgumentException("Missing '" + ProtocolStrings.TAG_EDGE_ID + "'");
        }
        newStartNodeId = TagUtil.getUUID(tag, ProtocolStrings.TAG_NEW_START_NODE_ID);
        if (newStartNodeId == null) {
            throw new IllegalArgumentException("Missing '" + ProtocolStrings.TAG_NEW_START_NODE_ID + "'");
        }
        newEndNodeId = TagUtil.getUUID(tag, ProtocolStrings.TAG_NEW_END_NODE_ID);
        if (newEndNodeId == null) {
            throw new IllegalArgumentException("Missing '" + ProtocolStrings.TAG_NEW_END_NODE_ID + "'");
        }
    }
}
