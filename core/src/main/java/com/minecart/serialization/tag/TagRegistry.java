package com.minecart.serialization.tag;

import com.google.gson.JsonElement;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public class TagRegistry {
    protected static final Map<Byte, Supplier<Tag>> REGISTRY = new HashMap<>();

    static {
        register(BoolTag.ID, BoolTag::new);
        register(IntTag.ID, IntTag::new);
        register(DoubleTag.ID, DoubleTag::new);
        register(StringTag.ID, StringTag::new);
        register(CompoundTag.ID, CompoundTag::new);
        register(ListTag.ID, ListTag::new);
    }

    public static Tag createInstance(byte id) {
        Supplier<Tag> supplier = REGISTRY.get(id);
        if (supplier == null) {
            throw new IllegalArgumentException("Corrupted save file: Unknown Tag ID " + id);
        }
        return supplier.get();
    }

    public static void register(byte id, Supplier<Tag> factory) {
        if (REGISTRY.containsKey(id)) {
            throw new IllegalArgumentException("Tag ID " + id + " is already registered!");
        }
        REGISTRY.put(id, factory);
    }
}
