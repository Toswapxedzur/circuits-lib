package com.minecart.serialization;

import com.minecart.serialization.tag.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Ensures {@link TagSerializable} exists and is distinct from {@link java.io.Serializable}.
 */
class TagSerializableNamingTest {

    @Test
    void tagSerializableSaveLoad() {
        CompoundTag tag = new CompoundTag();
        TagSerializable impl = new TagSerializable() {
            @Override
            public void save(CompoundTag t) {
                t.putString("x", "ok");
            }

            @Override
            public void load(CompoundTag t) {
                assertEquals("ok", t.getString("x"));
            }
        };
        impl.save(tag);
        impl.load(tag);
    }
}
