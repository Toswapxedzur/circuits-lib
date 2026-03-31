package com.minecart.serialization.tag;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TagWalkTest {

    @Test
    void walkVisitsPreOrderDepthFirst() {
        CompoundTag root = new CompoundTag();
        root.putString("a", "x");
        CompoundTag inner = new CompoundTag();
        inner.putBoolean("b", true);
        root.put("inner", inner);
        ListTag list = new ListTag();
        list.add(new DoubleTag(1.0));
        root.put("list", list);

        List<Byte> ids = new ArrayList<>();
        root.walk(t -> ids.add(t.getId()));

        assertEquals(CompoundTag.ID, ids.get(0));
        assertEquals(StringTag.ID, ids.get(1));
        assertEquals(CompoundTag.ID, ids.get(2));
        assertEquals(BoolTag.ID, ids.get(3));
        assertEquals(ListTag.ID, ids.get(4));
        assertEquals(DoubleTag.ID, ids.get(5));
    }

    @Test
    void walkLeafOnlySelf() {
        List<Tag> seen = new ArrayList<>();
        new IntTag(42).walk(seen::add);
        assertEquals(1, seen.size());
        assertTrue(seen.get(0) instanceof IntTag);
    }

    @Test
    void walkDescendantsSkipsRoot() {
        CompoundTag root = new CompoundTag();
        root.putString("a", "x");
        List<Tag> seen = new ArrayList<>();
        root.walkDescendants(seen::add);
        assertEquals(1, seen.size());
        assertTrue(seen.get(0) instanceof StringTag);
    }
}
