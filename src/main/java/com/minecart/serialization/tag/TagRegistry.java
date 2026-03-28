package com.minecart.serialization.tag;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class TagRegistry {
    protected static final Map<Byte, Supplier<Tag>> REGISTRY = new HashMap<>();
    protected static final Map<Byte, Predicate<JsonElement>> JSON = new HashMap<>();

    static {
        register(BoolTag.ID, ()->new BoolTag(), json -> json instanceof JsonPrimitive prim && prim.isBoolean());
        register(IntTag.ID, ()->new IntTag(), json -> false);
        register(DoubleTag.ID, ()->new DoubleTag(), json -> json instanceof JsonPrimitive prim && prim.isNumber());
        register(StringTag.ID, ()->new StringTag(), json -> json instanceof JsonPrimitive prim && prim.isString());
        register(CompoundTag.ID, ()->new CompoundTag(), JsonElement::isJsonObject);
        register(ListTag.ID, ()->new ListTag(), JsonElement::isJsonArray);
    }

    public static Tag createInstance(byte id) {
        Supplier<Tag> supplier = REGISTRY.get(id);
        if (supplier == null) {
            throw new IllegalArgumentException("Corrupted save file: Unknown Tag ID " + id);
        }
        return supplier.get();
    }

    public static void register(byte id, Supplier<Tag> factory, Predicate<JsonElement> predicate) {
        if (REGISTRY.containsKey(id) || JSON.containsKey(id)) {
            throw new IllegalArgumentException("Tag ID " + id + " is already registered!");
        }
        REGISTRY.put(id, factory);
        JSON.put(id, predicate);
    }

    public static Tag parseJson(JsonElement element) throws IOException {
        for(Map.Entry<Byte, Predicate<JsonElement>> entry : JSON.entrySet()){
            if(entry.getValue().test(element)){
                return createInstance(entry.getKey());
            }
        }
        throw new IOException("Failed to identify json");
    }
}