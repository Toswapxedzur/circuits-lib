package com.minecart.client.payload.client.action;

import com.minecart.action.Action;
import com.minecart.client.payload.Payload;
import com.minecart.client.payload.PayloadRegistry;
import com.minecart.client.payload.PayloadType;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;

import java.util.UUID;

/**
 * Data for a client → server action: world, circuit, element ids and {@link Action}. Wire format here;
 * execution is {@link ActionPayloadHandler}.
 */
public final class ActionPayload implements Payload {

    public static final String PAYLOAD_ID = "minecart.action_payload";

    public static final PayloadType<ActionPayload> TYPE =
            PayloadRegistry.register(PAYLOAD_ID, ActionPayload::new);

    private static final String TAG_WORLD_ID = "world_id";
    private static final String TAG_CIRCUIT_ID = "circuit_id";
    private static final String TAG_ELEMENT_ID = "element_id";
    private static final String TAG_ACTION = "action";

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
        Payload.writeEnvelope(tag, this);
        TagUtil.putUUID(tag, TAG_WORLD_ID, worldId);
        TagUtil.putUUID(tag, TAG_CIRCUIT_ID, circuitId);
        TagUtil.putUUID(tag, TAG_ELEMENT_ID, elementId);
        CompoundTag actionTag = new CompoundTag();
        action.save(actionTag);
        tag.put(TAG_ACTION, actionTag);
    }

    @Override
    public void load(CompoundTag tag) {
        Payload.verifyEnvelope(tag, getPayloadId());
        worldId = TagUtil.getUUID(tag, TAG_WORLD_ID);
        circuitId = TagUtil.getUUID(tag, TAG_CIRCUIT_ID);
        elementId = TagUtil.getUUID(tag, TAG_ELEMENT_ID);
        if (circuitId == null) {
            throw new IllegalArgumentException("Missing '" + TAG_CIRCUIT_ID + "'");
        }
        if (elementId == null) {
            throw new IllegalArgumentException("Missing '" + TAG_ELEMENT_ID + "'");
        }
        CompoundTag actionTag = TagUtil.requireCompoundTag(tag.get(TAG_ACTION), TAG_ACTION);
        action = Action.loadAction(actionTag);
    }
}
