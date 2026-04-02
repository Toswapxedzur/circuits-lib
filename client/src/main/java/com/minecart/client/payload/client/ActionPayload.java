package com.minecart.client.payload.client;

import com.minecart.action.Action;
import com.minecart.action.Actionable;
import com.minecart.client.payload.Payload;
import com.minecart.client.payload.PayloadRegistry;
import com.minecart.client.payload.PayloadType;
import com.minecart.logic.Circuit;
import com.minecart.logic.CircuitElement;
import com.minecart.logic.Level;
import com.minecart.logic.World;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;

import java.util.UUID;

/**
 * Network payload for a client → server action request.
 * Holds {@linkplain #getWorldId() world}, {@linkplain #getCircuitId() circuit}, and element ids plus the {@link Action}.
 * Serialize with {@link Payload#serialize(Payload)} or {@link #save(CompoundTag)}; decode with
 * {@link Payload#deserialize(CompoundTag)} or {@link #load(CompoundTag)} on a new {@link ActionPayload}.
 * On the server, use {@link #toActionable(Level)} to resolve from ids, or {@link #toActionable(Circuit)} when the circuit is already known.
 */
public class ActionPayload extends Payload {

    public static final String PAYLOAD_ID = "minecart.action_payload";

    /** Registered with {@link PayloadRegistry}; use {@link PayloadRegistry#load} to decode arbitrary payloads. */
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

    /** For deserialization; call {@link #load(CompoundTag)} before accessing fields. */
    public ActionPayload() {
    }

    public ActionPayload(UUID worldId, UUID circuitId, UUID elementId, Action action) {
        this.worldId = worldId;
        this.circuitId = circuitId;
        this.elementId = elementId;
        this.action = action;
    }

    /**
     * Builds a payload from an element that already has {@link CircuitElement#getWorld()} and {@link CircuitElement#getCircuit()}.
     */
    public static ActionPayload of(CircuitElement element, Action action) {
        World w = element.getWorld();
        Circuit c = element.getCircuit();
        if (w == null || c == null) {
            throw new IllegalArgumentException("Element must have world and circuit set");
        }
        return new ActionPayload(w.getId(), c.getId(), element.getId(), action);
    }

    @Override
    public String getPayloadId() {
        return PAYLOAD_ID;
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

    /**
     * Resolves to an {@link Actionable} using {@link Actionable#fromPayload(com.minecart.logic.Level, UUID, UUID, UUID, Action)}.
     * {@link #getWorldId()} may be {@code null} when only a single circuit is synced; the level then locates the
     * circuit by {@link #getCircuitId()} across all worlds.
     *
     * @throws IllegalArgumentException if ids do not resolve
     */
    public Runnable toActionable(Level level) {
        return Actionable.fromPayload(level, worldId, circuitId, elementId, action);
    }

    /**
     * Resolves the element in {@code circuit}. Use when the circuit is already known (e.g. editor focused on one circuit);
     * world id in the payload may be absent.
     *
     * @throws IllegalArgumentException if {@code circuit}'s id does not match {@link #getCircuitId()} when that is set,
     *                                  or the element is missing
     */
    public Runnable toActionable(Circuit circuit) {
        if (circuitId != null && !circuitId.equals(circuit.getId())) {
            throw new IllegalArgumentException("Circuit id mismatch: payload " + circuitId + ", actual " + circuit.getId());
        }
        return Actionable.fromPayload(circuit, elementId, action);
    }

    @Override
    protected void savePayload(CompoundTag tag) {
        TagUtil.putUUID(tag, TAG_WORLD_ID, worldId);
        TagUtil.putUUID(tag, TAG_CIRCUIT_ID, circuitId);
        TagUtil.putUUID(tag, TAG_ELEMENT_ID, elementId);
        CompoundTag actionTag = new CompoundTag();
        action.save(actionTag);
        tag.put(TAG_ACTION, actionTag);
    }

    @Override
    protected void loadPayload(CompoundTag tag) {
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
