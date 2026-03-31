package com.minecart.client;

import com.minecart.action.Action;
import com.minecart.action.Actionable;
import com.minecart.action.Actions;
import com.minecart.logic.Circuit;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;

import java.io.IOException;
import java.util.UUID;

/**
 * Network payload for a client → server action request.
 * Holds the target {@link UUID element id} and the {@link Action} to apply.
 * <p>
 * Serialize with {@link #save(CompoundTag)}, send across Netty, then reconstruct with
 * {@link ActionPayload#ActionPayload()} and {@link #load(CompoundTag)}.
 * On the server, call {@link #toActionable(Circuit)} to build a {@link Runnable} for
 * {@link com.minecart.logic.ServerLevel#submit(Runnable)}.
 */
public class ActionPayload extends Payload {

    public static final String PAYLOAD_ID = "minecart.action_payload";

    private static final String TAG_ELEMENT_ID = "element_id";
    private static final String TAG_ACTION = "action";

    private UUID elementId;
    private Action action;

    /** For deserialization; call {@link #load(CompoundTag)} before accessing fields. */
    public ActionPayload() {
    }

    public ActionPayload(UUID elementId, Action action) {
        this.elementId = elementId;
        this.action = action;
    }

    @Override
    public String getPayloadId() {
        return PAYLOAD_ID;
    }

    public UUID getElementId() {
        return elementId;
    }

    public Action getAction() {
        return action;
    }

    /**
     * Resolves the element in {@code circuit} and returns a ready-to-submit {@link Runnable}.
     *
     * @throws IllegalArgumentException if no element with {@link #getElementId()} exists in the circuit
     */
    public Runnable toActionable(Circuit circuit) {
        return Actionable.fromPayload(circuit, elementId, action);
    }

    @Override
    protected void savePayload(CompoundTag tag) throws IOException {
        TagUtil.putUUID(tag, TAG_ELEMENT_ID, elementId);
        CompoundTag actionTag = new CompoundTag();
        action.save(actionTag);
        tag.put(TAG_ACTION, actionTag);
    }

    @Override
    protected void loadPayload(CompoundTag tag) throws IOException {
        elementId = TagUtil.getUUID(tag, TAG_ELEMENT_ID);
        if (elementId == null) {
            throw new IOException("Missing '" + TAG_ELEMENT_ID + "'");
        }
        CompoundTag actionTag = TagUtil.requireCompoundTag(tag.get(TAG_ACTION), TAG_ACTION);
        action = Actions.readAction(actionTag);
    }
}
