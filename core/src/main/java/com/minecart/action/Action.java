package com.minecart.action;

import com.minecart.misc.CoreStrings;
import com.minecart.serialization.TagSerializable;
import com.minecart.serialization.tag.CompoundTag;

/**
 * Represent an Action conduct against a circuit element, only contain information, but not how to handle them.
 * Serializable to {@link CompoundTag} for network transport; use {@link Actions#readAction(CompoundTag)} to deserialize.
 */
public interface Action extends TagSerializable {

    String TAG_ACTION_ID = CoreStrings.ACTION_TAG_ID;

    /** Registry key used with {@link com.minecart.registry.CircuitElementType} handlers for this action. */
    ActionType<? extends Action> getActionType();

    public static Action loadAction(CompoundTag tag) {
        String id = tag.getString(TAG_ACTION_ID);
        ActionType<?> actionType = ActionRegistry.TYPES.get(id);
        if (actionType == null) {
            throw new IllegalArgumentException("Unknown action registry id: " + id);
        }
        return actionType.createFromTag(tag);
    }

    public static CompoundTag saveAction(Action action) {
        CompoundTag tag = new CompoundTag();
        tag.putString(TAG_ACTION_ID, action.getActionType().getId());
        action.save(tag);
        return tag;
    }
}
