package com.minecart.serialization.tag;

import com.google.gson.JsonElement;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * Inspired by minecraft tag system
 */
public abstract class Tag {
    public abstract byte getId();

    public abstract void readData(DataInput input) throws IOException;

    public abstract void writeData(DataOutput output) throws IOException;

    public abstract void readJson(JsonElement element) throws IOException;

    public abstract JsonElement writeJson();

    /**
     * Whether this tag type can deserialize {@code element}. {@link TagRegistry#parseJson}
     * tries registered tag types in order; the first match wins.
     */
    public abstract boolean matchesJson(JsonElement element);
}
