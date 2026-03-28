package com.minecart.serialization.tag;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class CompoundTag extends Tag {
    public static final byte ID = 10;
    private final Map<String, Tag> entries = new HashMap<>();

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

    // --- Binary IO ---
    @Override
    public void writeData(DataOutput output) throws IOException {
        for (Map.Entry<String, Tag> entry : entries.entrySet()) {
            output.writeByte(entry.getValue().getId());
            output.writeUTF(entry.getKey());
            entry.getValue().writeData(output);
        }
        output.writeByte(0); // 0 = EndTag marker
    }

    @Override
    public void readData(DataInput input) throws IOException {
        entries.clear();
        byte typeId;
        while ((typeId = input.readByte()) != 0) {
            String key = input.readUTF();
            Tag tag = TagRegistry.createInstance(typeId);
            if (tag != null) {
                tag.readData(input);
                entries.put(key, tag);
            } else {
                throw new IOException("Unknown Tag ID: " + typeId);
            }
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
                Tag tag = TagRegistry.parseJson(val);
                if (tag != null) {
                    tag.readJson(val);
                    entries.put(entry.getKey(), tag);
                }
            }
        }
    }
}