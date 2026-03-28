package com.minecart.serialization.tag;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public class StringTag extends Tag {
    public static final byte ID = 8;
    private String data;

    public StringTag() {}
    public StringTag(String data) { this.data = data; }

    @Override public byte getId() { return ID; }

    @Override public void readData(DataInput input) throws IOException { this.data = input.readUTF(); }
    @Override public void writeData(DataOutput output) throws IOException { output.writeUTF(this.data); }

    @Override public void readJson(JsonElement element) { this.data = element.getAsString(); }
    @Override public JsonElement writeJson() { return new JsonPrimitive(this.data); }

    public String getAsString() { return data; }
}