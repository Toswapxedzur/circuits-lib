package com.minecart.action;

import com.minecart.serialization.tag.CompoundTag;

import java.util.function.Supplier;

/**
 * Registered action kind: {@link #getId()} is written when serializing; {@link #createFromTag(CompoundTag)}
 * creates an instance via {@link Supplier} and fills it with {@link Action#load(CompoundTag)}.
 */
public class ActionType<T extends Action> {
    protected final String id;
    protected final Supplier<T> factory;

    public ActionType(String id, Supplier<T> factory) {
        this.id = id;
        this.factory = factory;
    }

    /** Registry id (same string passed to {@link ActionRegistry#register(ActionType)}). */
    public String getId() {
        return id;
    }

    /** Creates a new instance and {@link Action#load(CompoundTag)}s it from {@code tag}. */
    public T createFromTag(CompoundTag tag) {
        T action = factory.get();
        action.load(tag);
        return action;
    }

    /** @deprecated use {@link #getId()} */
    @Deprecated
    public String getName() {
        return id;
    }
}
