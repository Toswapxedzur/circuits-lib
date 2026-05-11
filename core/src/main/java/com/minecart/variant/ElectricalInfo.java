package com.minecart.variant;

import com.minecart.serialization.TagSerializable;
import com.minecart.serialization.tag.CompoundTag;

/**
 * Base electrical parameter bag for variants. Concrete subclasses serialize only their own fields; no type id is
 * written (callers must know which subtype is being loaded).
 */
public class ElectricalInfo implements TagSerializable {

    public ElectricalInfo() {
    }

    @Override
    public void save(CompoundTag tag) {
    }

    @Override
    public void load(CompoundTag tag) {
    }
}
