package com.minecart.client.payload.client;

import com.minecart.action.Action;
import com.minecart.client.misc.ClientStrings;
import com.minecart.client.payload.Payload;
import com.minecart.client.payload.PayloadRegistry;
import com.minecart.client.payload.PayloadType;
import com.minecart.client.handler.client.ActionPayloadHandler;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;

import java.util.UUID;

/**
 * Data for a client → server action: world, circuit, element ids and {@link Action}. Wire format here;
 * execution is {@link ActionPayloadHandler}.
 */
public final class ActionPayload implements Payload {

    public static final String PAYLOAD_ID = ClientStrings.PAYLOAD_ACTION;

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
        TagUtil.putUUID(tag, ClientStrings.TAG_WORLD_ID, worldId);
        TagUtil.putUUID(tag, ClientStrings.TAG_CIRCUIT_ID, circuitId);
        TagUtil.putUUID(tag, ClientStrings.TAG_ELEMENT_ID, elementId);
        CompoundTag actionTag = Action.saveAction(action);
        tag.put(ClientStrings.TAG_ACTION, actionTag);
    }

    @Override
    public void load(CompoundTag tag) {
        Payload.super.load(tag);
        worldId = TagUtil.getUUID(tag, ClientStrings.TAG_WORLD_ID);
        circuitId = TagUtil.getUUID(tag, ClientStrings.TAG_CIRCUIT_ID);
        elementId = TagUtil.getUUID(tag, ClientStrings.TAG_ELEMENT_ID);
        if (circuitId == null) {
            throw new IllegalArgumentException("Missing '" + ClientStrings.TAG_CIRCUIT_ID + "'");
        }
        if (elementId == null) {
            throw new IllegalArgumentException("Missing '" + ClientStrings.TAG_ELEMENT_ID + "'");
        }
        CompoundTag actionTag = TagUtil.requireCompoundTag(tag.get(ClientStrings.TAG_ACTION), ClientStrings.TAG_ACTION);
        action = Action.loadAction(actionTag);
    }
}
