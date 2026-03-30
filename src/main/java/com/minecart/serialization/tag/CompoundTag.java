package com.minecart.serialization.tag;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.minecart.serialization.SerializationContext;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CompoundTag extends Tag {
    public static final byte ID = 10;

    private static final byte KEY_MODE_UTF = 0;
    private static final byte KEY_MODE_CONTEXT = 1;

    /** Insertion order so {@link Tag#bindAllKeysRecursive} matches write order. */
    private final Map<String, Tag> entries = new LinkedHashMap<>();

    @Override
    public byte getId() { return ID; }

    // --- Convenience Setters ---
    public void put(String key, Tag tag) { entries.put(key, tag); }
    public void putBoolean(String key, boolean value) { entries.put(key, new BoolTag(value)); }
    public void putInt(String key, int value) { entries.put(key, new IntTag(value)); }
    public void putDouble(String key, double value) { entries.put(key, new DoubleTag(value)); }
    public void putString(String key, String value) { entries.put(key, new StringTag(value)); }

    // --- Convenience Getters ---
    public Tag get(String key) { return entries.get(key); }

    public boolean getBoolean(String key) {
        return get(key) instanceof BoolTag tag ? tag.getAsBoolean() : false;
    }
    public int getInt(String key) {
        if(get(key) instanceof IntTag tag)
            return tag.getAsInt();
        if(get(key) instanceof DoubleTag tag)
            return (int) tag.getAsDouble();
        return 0;
    }
    public double getDouble(String key) {
        if(get(key) instanceof DoubleTag tag)
            return tag.getAsDouble();
        if(get(key) instanceof IntTag tag)
            return (double) tag.getAsInt();
        return 0.0;
    }
    public String getString(String key) {
        return get(key) instanceof StringTag tag ? tag.getAsString() : "";
    }

    public Set<String> keySet() {
        return Collections.unmodifiableSet(entries.keySet());
    }

    /** All entry values in insertion order (keys are not included — use {@link #keySet()} / {@link #get(String)}). */
    @Override
    public List<Tag> children() {
        return Collections.unmodifiableList(new ArrayList<>(entries.values()));
    }

    private static boolean useContextualKeys(SerializationContext context) {
        return context != null && !context.isEmpty();
    }

    // --- Binary IO ---
    @Override
    public void writeData(DataOutput output, SerializationContext context) throws IOException {
        for (Map.Entry<String, Tag> entry : entries.entrySet()) {
            String key = entry.getKey();
            Tag value = entry.getValue();
            output.writeByte(value.getId());
            if (useContextualKeys(context)) {
                short id = context.bind(key);
                output.writeByte(KEY_MODE_CONTEXT);
                output.writeShort(id);
            } else {
                output.writeUTF(key);
            }
            value.writeData(output, context);
        }
        output.writeByte(0); // 0 = EndTag marker
    }

    @Override
    public void readData(DataInput input, SerializationContext context) throws IOException {
        entries.clear();
        byte typeId;
        while ((typeId = input.readByte()) != 0) {
            String key;
            if (useContextualKeys(context)) {
                byte keyMode = input.readByte();
                if (keyMode == KEY_MODE_CONTEXT) {
                    short id = input.readShort();
                    key = context.keyOf(id);
                    if (key == null) {
                        throw new IOException("Unknown serialization context key id: " + id);
                    }
                } else if (keyMode == KEY_MODE_UTF) {
                    key = input.readUTF();
                } else {
                    throw new IOException("Unknown compound key mode: " + keyMode);
                }
            } else {
                key = input.readUTF();
            }
            Tag tag = TagRegistry.createInstance(typeId);
            tag.readData(input, context);
            entries.put(key, tag);
        }
    }

    // --- JSON IO ---
    @Override
    public JsonElement writeJson() {
        JsonObject json = new JsonObject();
        for (Map.Entry<String, Tag> entry : entries.entrySet()) {
            json.add(entry.getKey(), entry.getValue().writeJson());
        }
        return json;
    }

    @Override
    public void readJson(JsonElement element) throws IOException {
        entries.clear();
        if (element.isJsonObject()) {
            JsonObject jsonObject = element.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : jsonObject.entrySet()) {
                JsonElement val = entry.getValue();
                Tag tag = Tag.parseJson(val);
                entries.put(entry.getKey(), tag);
            }
        }
    }

    @Override
    public boolean matchesJson(JsonElement element) {
        return element.isJsonObject();
    }
}
