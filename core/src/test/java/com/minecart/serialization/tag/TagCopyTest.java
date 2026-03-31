package com.minecart.serialization.tag;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.minecart.serialization.TagUtil;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class TagCopyTest {

    private static final Gson GSON = new GsonBuilder().create();

    @Test
    void copyIsDeepIndependentOfOriginal() throws IOException {
        CompoundTag a = new CompoundTag();
        a.putString("k", "v");
        CompoundTag inner = new CompoundTag();
        inner.putBoolean("b", true);
        a.put("nested", inner);

        CompoundTag b = a.copy();
        assertNotSame(a, b);
        assertNotSame(a.get("nested"), b.get("nested"));

        ((CompoundTag) a.get("nested")).putString("x", "changed");

        assertEquals("v", b.getString("k"));
        CompoundTag bInner = (CompoundTag) b.get("nested");
        assertEquals("", bInner.getString("x"));
        assertEquals(true, bInner.getBoolean("b"));
    }

    @Test
    void copyPreservesBinaryRoundTrip() throws IOException {
        CompoundTag original = new CompoundTag();
        UUID id = UUID.randomUUID();
        original.putString("id", "test");
        TagUtil.putUUID(original, "uuid", id);
        ListTag list = new ListTag();
        list.add(new DoubleTag(1.0));
        original.put("list", list);

        CompoundTag copy = original.copy();

        String jsonOrig = GSON.toJson(original.writeJson());
        String jsonCopy = GSON.toJson(copy.writeJson());
        assertEquals(jsonOrig, jsonCopy);

        byte[] bytes;
        try (ByteArrayOutputStream buf = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(buf)) {
            Tag.writeBinary(out, copy);
            bytes = buf.toByteArray();
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            Tag.BinaryWithContext decoded = Tag.readBinary(in);
            CompoundTag round = (CompoundTag) decoded.root();
            assertEquals(jsonOrig, GSON.toJson(round.writeJson()));
        }
    }
}
