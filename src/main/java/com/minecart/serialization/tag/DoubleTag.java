package com.minecart.serialization.tag;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public class DoubleTag extends Tag {
    public static final byte ID = 6;
    private double data;

    public DoubleTag() {}
    public DoubleTag(double data) { this.data = data; }

    @Override public byte getId() { return ID; }

    @Override public void readData(DataInput input) throws IOException { this.data = input.readDouble(); }
    @Override public void writeData(DataOutput output) throws IOException { output.writeDouble(this.data); }

    @Override public void readJson(JsonElement element) {
        this.data = element.getAsNumber().doubleValue();
    }
    @Override public JsonElement writeJson() { return new JsonPrimitive(this.data); }

    @Override
    public boolean matchesJson(JsonElement element) {
        return element instanceof JsonPrimitive prim && prim.isNumber();
    }

    public double getAsDouble() { return data; }
}