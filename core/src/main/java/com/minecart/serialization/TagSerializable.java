package com.minecart.serialization;

import com.minecart.serialization.tag.CompoundTag;

/**
 * Load/save objects to {@link CompoundTag} data. Named {@code TagSerializable} to avoid confusion
 * with {@link java.io.Serializable}.
 */
public interface TagSerializable {
    void save(CompoundTag tag);

    void load(CompoundTag tag);
}
