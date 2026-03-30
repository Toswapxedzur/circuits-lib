package com.minecart.serialization.tag;

import com.google.gson.JsonElement;
import com.minecart.serialization.SerializationContext;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Inspired by minecraft tag system
 */
public abstract class Tag {

    /** Result of {@link #readBinary(DataInput)}: context blob then root tag. */
    public static final class BinaryWithContext {
        private final SerializationContext context;
        private final Tag root;

        public BinaryWithContext(SerializationContext context, Tag root) {
            this.context = Objects.requireNonNull(context, "context");
            this.root = Objects.requireNonNull(root, "root");
        }

        public SerializationContext context() {
            return context;
        }

        public Tag root() {
            return root;
        }
    }

    /** Dispatches on JSON shape; see {@link TagRegistry}. */
    public static Tag parseJson(JsonElement element) throws IOException {
        for (Supplier<Tag> factory : TagRegistry.JSON_TAG_ORDER) {
            Tag tag = factory.get();
            if (tag.matchesJson(element)) {
                tag.readJson(element);
                return tag;
            }
        }
        throw new IOException("Failed to identify json");
    }

    /** Serializes any tag to JSON. */
    public static JsonElement toJson(Tag tag) {
        Objects.requireNonNull(tag, "tag");
        return tag.writeJson();
    }

    /**
     * Reads a {@link SerializationContext} blob, then a type id byte and root tag payload.
     * Nested compounds use the same context when it has bindings; otherwise legacy UTF keys.
     */
    public static BinaryWithContext readBinary(DataInput input) throws IOException {
        SerializationContext context = new SerializationContext();
        context.readData(input);
        byte id = input.readByte();
        Tag tag = TagRegistry.createInstance(id);
        tag.readData(input, context);
        return new BinaryWithContext(context, tag);
    }

    /**
     * Writes {@link SerializationContext} first, then type id byte and root tag payload.
     * All compound keys are {@link SerializationContext#bind(String) bound} into {@code context}
     * before the context blob is written so the dictionary matches the tag tree.
     */
    public static void writeBinary(DataOutput output, SerializationContext context, Tag tag) throws IOException {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(tag, "tag");
        bindAllKeysRecursive(context, tag);
        context.writeData(output);
        output.writeByte(tag.getId());
        tag.writeData(output, context);
    }

    /**
     * Writes a fresh {@link SerializationContext} (keys bound from the tag tree) then the tag.
     */
    public static void writeBinary(DataOutput output, Tag tag) throws IOException {
        writeBinary(output, new SerializationContext(), tag);
    }

    /**
     * Binds every compound key in {@code tag} (depth-first, same order as binary write) so the
     * context dictionary is complete before it is serialized.
     */
    public static void bindAllKeysRecursive(SerializationContext context, Tag tag) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(tag, "tag");
        if (tag instanceof CompoundTag compound) {
            for (String key : compound.keySet()) {
                context.bind(key);
            }
        }
        for (Tag child : tag.children()) {
            bindAllKeysRecursive(context, child);
        }
    }

    /**
     * Direct child tags in structural order. {@link CompoundTag} returns map values (insertion order);
     * {@link ListTag} returns list elements. Leaf tags return an empty list.
     */
    public List<Tag> children() {
        return Collections.emptyList();
    }

    public abstract byte getId();

    public abstract void readData(DataInput input, SerializationContext context) throws IOException;

    public abstract void writeData(DataOutput output, SerializationContext context) throws IOException;

    public abstract void readJson(JsonElement element) throws IOException;

    public abstract JsonElement writeJson();

    /**
     * Whether this tag type can deserialize {@code element}. {@link TagRegistry#parseJson}
     * tries registered tag types in order; the first match wins.
     */
    public abstract boolean matchesJson(JsonElement element);
}
