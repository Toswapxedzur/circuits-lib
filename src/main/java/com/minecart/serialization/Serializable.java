package com.minecart.serialization;

import com.minecart.serialization.tag.CompoundTag;

public interface Serializable {
    void save(CompoundTag tag);

    void load(CompoundTag tag);
}
