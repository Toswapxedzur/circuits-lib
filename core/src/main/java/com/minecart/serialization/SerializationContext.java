package com.minecart.serialization;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Maps compound field names to {@code short} ids for compact binary encoding.
 * Can be written and read as a self-contained binary blob (e.g. at the start of a save file).
 */
public class SerializationContext {

    private final Map<String, Short> keyToId = new HashMap<>();
    private final Map<Short, String> idToKey = new HashMap<>();

    public void clear() {
        keyToId.clear();
        idToKey.clear();
    }

    public int size() {
        return keyToId.size();
    }

    public boolean isEmpty() {
        return keyToId.isEmpty();
    }

    /**
     * Binds {@code id} to {@code key}. Both must be unique within this context.
     */
    public void bind(short id, String key) {
        Objects.requireNonNull(key, "key");
        String existingKey = idToKey.get(id);
        if (existingKey != null && !existingKey.equals(key)) {
            throw new IllegalArgumentException("Id " + id + " already bound to \"" + existingKey + "\"");
        }
        Short existingId = keyToId.get(key);
        if (existingId != null && existingId != id) {
            throw new IllegalArgumentException("Key \"" + key + "\" already bound to id " + existingId);
        }
        idToKey.put(id, key);
        keyToId.put(key, id);
    }

    /**
     * Ensures {@code key} is bound and returns its id. If new, assigns the lowest unused {@code short} id
     * and delegates to {@link #bind(short, String)}.
     */
    public short bind(String key) {
        Objects.requireNonNull(key, "key");
        Short existing = keyToId.get(key);
        if (existing != null) {
            return existing;
        }
        short id = (short) findFirstFreeId();
        bind(id, key);
        return id;
    }

    private int findFirstFreeId() {
        for (int i = 0; i <= 0xFFFF; i++) {
            short s = (short) i;
            if (!idToKey.containsKey(s)) {
                return i;
            }
        }
        throw new IllegalStateException("No free short id");
    }

    public Short idOf(String key) {
        return keyToId.get(key);
    }

    public String keyOf(short id) {
        return idToKey.get(id);
    }

    /**
     * Binary format: {@code int} entry count, then for each entry {@code short id}, {@code UTF} key.
     * Entries are sorted by id ascending so output is stable.
     */
    public void writeData(DataOutput output) throws IOException {
        List<Short> ids = new ArrayList<>(idToKey.keySet());
        Collections.sort(ids);
        output.writeInt(ids.size());
        for (short id : ids) {
            output.writeShort(id);
            output.writeUTF(idToKey.get(id));
        }
    }

    /**
     * Replaces this context with the contents read from {@code input}.
     */
    public void readData(DataInput input) throws IOException {
        clear();
        int count = input.readInt();
        if (count < 0) {
            throw new IOException("Invalid SerializationContext entry count: " + count);
        }
        if (count > Character.MAX_VALUE + 1) {
            throw new IOException("SerializationContext entry count too large: " + count);
        }
        for (int i = 0; i < count; i++) {
            short id = input.readShort();
            String key = input.readUTF();
            if (idToKey.containsKey(id)) {
                throw new IOException("Duplicate id in SerializationContext: " + id);
            }
            if (keyToId.containsKey(key)) {
                throw new IOException("Duplicate key in SerializationContext: " + key);
            }
            idToKey.put(id, key);
            keyToId.put(key, id);
        }
    }

    public byte[] toByteArray() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            writeData(out);
        }
        return buffer.toByteArray();
    }

    public static SerializationContext fromByteArray(byte[] bytes) throws IOException {
        SerializationContext ctx = new SerializationContext();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            ctx.readData(in);
        }
        return ctx;
    }
}
