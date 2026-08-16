package com.minecart.protocol.payload.client;

import com.minecart.protocol.misc.ProtocolStrings;
import com.minecart.protocol.payload.Payload;
import com.minecart.protocol.payload.PayloadRegistry;
import com.minecart.protocol.payload.PayloadType;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;

import java.util.UUID;

/**
 * Client → server: "swap the {@link com.minecart.logic.CircuitNode} occupying a port slot of
 * {@code componentId} from {@code oldNodeId} to {@code newNodeId}". Routes through
 * {@link com.minecart.logic.CircuitComponent#replacePort} after the server validates that the old node
 * really is a registered port and that the new node's registry type matches (a port carries a fixed
 * electrical kind so a mismatched substitution would silently change the component's behaviour).
 *
 * <p>Used by the editor's node-combine flow when the absorbed node is a port of a component: the
 * dragged "survivor" node takes over the port slot so external wires keep landing at the same anchor,
 * and the component stays well-formed without inventing a new node id. Edge re-routing onto the new
 * port and removal of the old node travel as separate {@link EdgeEndpointChangePayload}s and a final
 * {@link DeleteElementPayload} so the protocol stays a flat sequence of granular edits.
 *
 * <p>Order matters: send this <em>before</em> any per-edge endpoint change that targets the new port,
 * so that internal-strut edges of the host component are repointed to a node that already lives in
 * the component (avoiding a transient "internal edge connecting to a node outside the component"
 * state that downstream consumers may not tolerate).
 *
 * <p>Silent no-op on unknown world / component / nodes, on a type mismatch, or when the slot doesn't
 * exist.
 */
public final class ReplaceComponentNodePayload implements Payload {

    public static final String PAYLOAD_ID = ProtocolStrings.PAYLOAD_REPLACE_COMPONENT_NODE;

    public static final PayloadType<ReplaceComponentNodePayload> TYPE =
            PayloadRegistry.register(PAYLOAD_ID, ReplaceComponentNodePayload::new);

    private UUID worldId;
    private UUID componentId;
    private UUID oldNodeId;
    private UUID newNodeId;

    public ReplaceComponentNodePayload() {
    }

    public ReplaceComponentNodePayload(UUID worldId, UUID componentId, UUID oldNodeId, UUID newNodeId) {
        this.worldId = worldId;
        this.componentId = componentId;
        this.oldNodeId = oldNodeId;
        this.newNodeId = newNodeId;
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
    public UUID getComponentId() { return componentId; }
    public UUID getOldNodeId() { return oldNodeId; }
    public UUID getNewNodeId() { return newNodeId; }

    @Override
    public void save(CompoundTag tag) {
        Payload.super.save(tag);
        TagUtil.putUUID(tag, ProtocolStrings.TAG_WORLD_ID, worldId);
        TagUtil.putUUID(tag, ProtocolStrings.TAG_COMPONENT_ID, componentId);
        TagUtil.putUUID(tag, ProtocolStrings.TAG_OLD_NODE_ID, oldNodeId);
        TagUtil.putUUID(tag, ProtocolStrings.TAG_NEW_NODE_ID, newNodeId);
    }

    @Override
    public void load(CompoundTag tag) {
        Payload.super.load(tag);
        worldId = Payload.requireUUID(tag, ProtocolStrings.TAG_WORLD_ID);
        componentId = Payload.requireUUID(tag, ProtocolStrings.TAG_COMPONENT_ID);
        oldNodeId = Payload.requireUUID(tag, ProtocolStrings.TAG_OLD_NODE_ID);
        newNodeId = Payload.requireUUID(tag, ProtocolStrings.TAG_NEW_NODE_ID);
    }
}
