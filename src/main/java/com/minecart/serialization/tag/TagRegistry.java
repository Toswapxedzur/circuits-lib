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
    /** Order matches {@link #register} calls — first {@link Tag#matchesJson} wins in {@link #parseJson}. */
    protected static final List<Supplier<Tag>> JSON_TAG_ORDER = new ArrayList<>();

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
        JSON_TAG_ORDER.add(factory);
    }

    public static Tag parseJson(JsonElement element) throws IOException {
        for (Supplier<Tag> factory : JSON_TAG_ORDER) {
            Tag tag = factory.get();
            if (tag.matchesJson(element)) {
                tag.readJson(element);
                return tag;
            }
        }
        throw new IOException("Failed to identify json");
    }
}
