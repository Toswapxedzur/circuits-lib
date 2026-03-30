package com.minecart.serialization;

import com.minecart.serialization.tag.*;

import java.util.UUID;

public class TagUtil {
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
            System.err.println("Malformed UUID Tag: List did not contain IntTags.");
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
}