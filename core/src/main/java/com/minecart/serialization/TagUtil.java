package com.minecart.serialization;

import com.minecart.serialization.tag.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

public class TagUtil {

    private static final Logger log = LoggerFactory.getLogger(TagUtil.class);
    public static int getInt(Tag numberTag) {
        if(numberTag instanceof IntTag tag)
            return tag.getAsInt();
        if(numberTag instanceof DoubleTag tag)
            return (int) tag.getAsDouble();
        return 0;
    }
    public static double getDouble(Tag numberTag) {
        if(numberTag instanceof DoubleTag tag)
            return tag.getAsDouble();
        if(numberTag instanceof IntTag tag)
            return (double) tag.getAsInt();
        return 0.0;
    }

    /**
     * Converts a UUID into a highly efficient ListTag containing exactly 4 integers.
     * This perfectly mimics modern NBT standards, saving 20+ bytes per UUID in binary.
     */
    public static ListTag writeUUID(UUID uuid) {
        if (uuid == null) return new ListTag();

        long most = uuid.getMostSignificantBits();
        long least = uuid.getLeastSignificantBits();

        ListTag list = new ListTag();
        // Break the two 64-bit longs into four 32-bit ints
        list.add(new IntTag((int) (most >> 32)));
        list.add(new IntTag((int) most));
        list.add(new IntTag((int) (least >> 32)));
        list.add(new IntTag((int) least));

        return list;
    }

    /**
     * Reconstructs a UUID from a ListTag of 4 integers.
     * Returns null if the tag is missing or malformed.
     */
    public static UUID readUUID(Tag tag) {
        if (!(tag instanceof ListTag list) || list.size() != 4) {
            return null;
        }

        try {
            int i0 = getInt(list.get(0));
            int i1 = getInt(list.get(1));
            int i2 = getInt(list.get(2));
            int i3 = getInt(list.get(3));

            // Reconstruct the two 64-bit longs using bitwise shifts
            long most = ((long) i0 << 32) | (i1 & 0xFFFFFFFFL);
            long least = ((long) i2 << 32) | (i3 & 0xFFFFFFFFL);

            return new UUID(most, least);

        } catch (ClassCastException e) {
            log.warn("Malformed UUID Tag: List did not contain IntTags.", e);
            return null;
        }
    }

    // --- Convenience Methods for CompoundTag Integration ---

    /**
     * Safely packs a UUID into a CompoundTag under the given key.
     */
    public static void putUUID(CompoundTag compound, String key, UUID uuid) {
        if (uuid != null) {
            compound.put(key, writeUUID(uuid));
        }
    }

    /**
     * Safely extracts a UUID from a CompoundTag.
     */
    public static UUID getUUID(CompoundTag compound, String key) {
        Tag tag = compound.get(key);
        return tag != null ? readUUID(tag) : null;
    }

    /** Legacy: parses a UUID from a {@link StringTag} list entry (older component id lists). Prefer {@link #readUUID}. */
    public static UUID parseUuidStringTag(Tag t) {
        if (t instanceof StringTag st) {
            try {
                return UUID.fromString(st.getAsString());
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * Returns {@code tag} as {@link CompoundTag}, or throws {@link IllegalArgumentException} with {@code message}.
     */
    public static CompoundTag requireCompoundTag(Tag tag, String message) {
        if (tag instanceof CompoundTag c) {
            return c;
        }
        throw new IllegalArgumentException(message);
    }

    /**
     * Writes {@code ids} as a {@link ListTag} of {@link #writeUUID(UUID)} entries under {@code key}.
     * Omits the key when {@code ids} is null or empty.
     */
    public static void putUuidList(CompoundTag compound, String key, List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        ListTag list = new ListTag();
        for (UUID id : ids) {
            if (id != null) {
                list.add(writeUUID(id));
            }
        }
        compound.put(key, list);
    }

    /**
     * Reads UUID entries from a {@link ListTag} at {@code key} into {@code out} (append).
     */
    public static void readUuidList(CompoundTag compound, String key, List<UUID> out) {
        Tag t = compound.get(key);
        if (!(t instanceof ListTag list)) {
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            UUID u = readUUID(list.get(i));
            if (u == null) {
                u = parseUuidStringTag(list.get(i));
            }
            if (u != null) {
                out.add(u);
            }
        }
    }

    /**
     * Writes {@code compounds} as a {@link ListTag} of compound children under {@code key}.
     * Omits the key when {@code compounds} is null or empty.
     */
    public static void putCompoundList(CompoundTag compound, String key, List<CompoundTag> compounds) {
        if (compounds == null || compounds.isEmpty()) {
            return;
        }
        ListTag list = new ListTag();
        for (CompoundTag c : compounds) {
            list.add(c);
        }
        compound.put(key, list);
    }

    /**
     * Reads {@link CompoundTag} entries from a {@link ListTag} at {@code key} into {@code out} (append).
     */
    public static void readCompoundList(CompoundTag compound, String key, List<CompoundTag> out) {
        Tag t = compound.get(key);
        if (!(t instanceof ListTag list)) {
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            out.add(requireCompoundTag(list.get(i), key + "[" + i + "]"));
        }
    }
}