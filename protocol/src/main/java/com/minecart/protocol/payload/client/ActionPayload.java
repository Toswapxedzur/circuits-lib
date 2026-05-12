package com.minecart.protocol.payload.client;

import com.minecart.action.Action;
import com.minecart.protocol.misc.ProtocolStrings;
import com.minecart.protocol.payload.Payload;
import com.minecart.protocol.payload.PayloadRegistry;
import com.minecart.protocol.payload.PayloadType;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;

import java.util.UUID;

/**
 * Data for a client → server action: world, circuit, element ids and {@link Action}. Wire format here;
 * execution lives in the server module.
 */
public final class ActionPayload implements Payload {

    public static final String PAYLOAD_ID = ProtocolStrings.PAYLOAD_ACTION;

    public static final PayloadType<ActionPayload> TYPE =
            PayloadRegistry.register(PAYLOAD_ID, ActionPayload::new);

    private UUID worldId;
    private UUID circuitId;
    private UUID elementId;
    private Action action;

    public ActionPayload() {
    }

    public ActionPayload(UUID worldId, UUID circuitId, UUID elementId, Action action) {
        this.worldId = worldId;
        this.circuitId = circuitId;
        this.elementId = elementId;
        this.action = action;
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

    public UUID getCircuitId() {
        return circuitId;
    }

    public UUID getElementId() {
        return elementId;
    }

    public Action getAction() {
        return action;
    }

    @Override
    public void save(CompoundTag tag) {
        Payload.super.save(tag);
        TagUtil.putUUID(tag, ProtocolStrings.TAG_WORLD_ID, worldId);
        TagUtil.putUUID(tag, ProtocolStrings.TAG_CIRCUIT_ID, circuitId);
        TagUtil.putUUID(tag, ProtocolStrings.TAG_ELEMENT_ID, elementId);
        CompoundTag actionTag = Action.saveAction(action);
        tag.put(ProtocolStrings.TAG_ACTION, actionTag);
    }

    @Override
    public void load(CompoundTag tag) {
        Payload.super.load(tag);
        worldId = TagUtil.getUUID(tag, ProtocolStrings.TAG_WORLD_ID);
        circuitId = TagUtil.getUUID(tag, ProtocolStrings.TAG_CIRCUIT_ID);
        elementId = TagUtil.getUUID(tag, ProtocolStrings.TAG_ELEMENT_ID);
        if (circuitId == null) {
            throw new IllegalArgumentException("Missing '" + ProtocolStrings.TAG_CIRCUIT_ID + "'");
        }
        if (elementId == null) {
            throw new IllegalArgumentException("Missing '" + ProtocolStrings.TAG_ELEMENT_ID + "'");
        }
        CompoundTag actionTag = TagUtil.requireCompoundTag(tag.get(ProtocolStrings.TAG_ACTION), ProtocolStrings.TAG_ACTION);
        action = Action.loadAction(actionTag);
    }
}
