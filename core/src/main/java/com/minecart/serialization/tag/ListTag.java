package com.minecart.serialization.tag;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.minecart.serialization.SerializationContext;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ListTag extends Tag {
    public static final byte ID = 9;

    // 0 means the list is empty and has no strict type yet
    private byte elementType = 0;
    private final List<Tag> list = new ArrayList<>();

    public ListTag() {}

    @Override
    public byte getId() { return ID; }

    public void add(Tag tag) {
        if (tag == null) return;

        // Lock in the type on the first insertion
        if (elementType == 0) {
            elementType = tag.getId();
        } else if (elementType != tag.getId()) {
            throw new IllegalArgumentException("Trying to add Tag ID " + tag.getId() + " to a ListTag of type " + elementType);
        }
        list.add(tag);
    }

    public Tag get(int index) {
        return list.get(index);
    }

    public int size() {
        return list.size();
    }

    public boolean isEmpty() {
        return list.isEmpty();
    }

    @Override
    public List<Tag> children() {
        return Collections.unmodifiableList(list);
    }

    @Override
    public ListTag copy() {
        ListTag n = new ListTag();
        for (Tag t : list) {
            n.add(t.copy());
        }
        return n;
    }

    // --- Binary IO ---
    @Override
    public void writeData(DataOutput output, SerializationContext context) throws IOException {
        if (list.isEmpty()) {
            output.writeByte(0); // Type 0 (EndTag/Empty)
            output.writeInt(0);  // Size 0
        } else {
            output.writeByte(elementType);
            output.writeInt(list.size());
            for (Tag tag : list) {
                tag.writeData(output, context);
            }
        }
    }

    @Override
    public void readData(DataInput input, SerializationContext context) throws IOException {
        list.clear();
        this.elementType = input.readByte();
        int size = input.readInt();

        if (this.elementType == 0 || size <= 0) {
            return; // Empty list
        }

        for (int i = 0; i < size; i++) {
            Tag tag = TagRegistry.createInstance(this.elementType);
            tag.readData(input, context);
            list.add(tag);
        }
    }

    // --- JSON IO ---
    @Override
    public JsonElement writeJson() {
        JsonArray array = new JsonArray();
        for (Tag tag : list) {
            array.add(tag.writeJson());
        }
        return array;
    }

    @Override
    public void readJson(JsonElement element) throws IOException {
        list.clear();
        elementType = 0;
        if (!element.isJsonArray()) {
            return;
        }
        JsonArray array = element.getAsJsonArray();
        List<Tag> parsed = new ArrayList<>();
        List<IOException> failures = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            JsonElement jsonElement = array.get(i);
            try {
                parsed.add(Tag.parseJson(jsonElement));
            } catch (IOException e) {
                failures.add(new IOException("index " + i + ": " + e.getMessage(), e));
            }
        }
        if (!failures.isEmpty()) {
            IOException aggregated = new IOException(
                    "Failed to parse " + failures.size() + " of " + array.size() + " list elements");
            for (IOException ex : failures) {
                aggregated.addSuppressed(ex);
            }
            throw aggregated;
        }
        for (Tag t : parsed) {
            add(t);
        }
    }

    @Override
    public boolean matchesJson(JsonElement element) {
        return element.isJsonArray();
    }
}
