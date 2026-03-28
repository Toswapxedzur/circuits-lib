package com.minecart.serialization.tag;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public class BoolTag extends Tag{
    public static final byte ID = 1;
    private boolean data;

    public BoolTag() {} // For the parser
    public BoolTag(boolean data) { this.data = data; }

    @Override public byte getId() { return ID; }

    @Override public void readData(DataInput input) throws IOException { this.data = input.readBoolean(); }

    @Override public void writeData(DataOutput output) throws IOException { output.writeBoolean(this.data); }

    @Override public void readJson(JsonElement element) { this.data = element.getAsBoolean(); }

    @Override public JsonElement writeJson() { return new JsonPrimitive(this.data); }

    public boolean getAsBoolean() { return data; }
}
