package com.minecart.serialization.tag;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * Will not be present after deserialization from a json, all number will be converted to double
 */
public class IntTag extends Tag {
    public static final byte ID = 3;
    private int data;

    public IntTag() {}
    public IntTag(int data) { this.data = data; }

    @Override public byte getId() { return ID; }

    @Override public void readData(DataInput input) throws IOException { this.data = input.readInt(); }
    @Override public void writeData(DataOutput output) throws IOException { output.writeInt(this.data); }

    @Override public void readJson(JsonElement element) { this.data = element.getAsInt(); }
    @Override public JsonElement writeJson() { return new JsonPrimitive(this.data); }

    @Override
    public boolean matchesJson(JsonElement element) {
        return false;
    }

    public int getAsInt() { return data; }
}