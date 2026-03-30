package com.minecart;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;
import com.minecart.serialization.tag.DoubleTag;
import com.minecart.serialization.tag.ListTag;
import com.minecart.serialization.tag.Tag;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

public class Main {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static void main(String[] args) {
        System.out.println("=== TAG SYSTEM TESTS ===\n");

        CompoundTag originalData = new CompoundTag();
        UUID componentId = UUID.randomUUID();

        originalData.putString("id", "minecart:battery");
        originalData.putInt("tick_delay", 20);
        originalData.putDouble("voltage", 12.5);
        originalData.putBoolean("is_active", true);
        TagUtil.putUUID(originalData, "uuid", componentId);

        ListTag historyList = new ListTag();
        historyList.add(new DoubleTag(11.2));
        historyList.add(new DoubleTag(12.0));
        historyList.add(new DoubleTag(12.5));
        originalData.put("tick_history", historyList);

        try {
            testJsonRoundTrip(originalData, componentId);
            System.out.println();
            testBinaryTagRoundTrip(originalData, componentId);
        } catch (IOException e) {
            System.err.println("Test failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void testJsonRoundTrip(CompoundTag original, UUID expectedUuid) throws IOException {
        System.out.println("--- JSON round-trip ---\n");

        JsonElement jsonOutput = original.writeJson();
        System.out.println(GSON.toJson(jsonOutput));

        CompoundTag loaded = new CompoundTag();
        loaded.readJson(jsonOutput);

        assertCondition("JSON: UUID preserved", expectedUuid.equals(TagUtil.getUUID(loaded, "uuid")));
        assertCondition("JSON: id string", "minecart:battery".equals(loaded.getString("id")));
        assertCondition("JSON: tick_delay", loaded.getInt("tick_delay") == 20);
    }

    private static void testBinaryTagRoundTrip(CompoundTag original, UUID expectedUuid) throws IOException {
        System.out.println("--- Binary round-trip (SerializationContext + Tag) ---\n");

        byte[] bytes;
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(buffer)) {
            Tag.writeBinary(out, original);
            bytes = buffer.toByteArray();
        }

        System.out.println("Binary size: " + bytes.length + " bytes");

        Tag.BinaryWithContext decoded;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            decoded = Tag.readBinary(in);
        }

        CompoundTag roundTrip = (CompoundTag) decoded.root();

        String jsonBefore = GSON.toJson(original.writeJson());
        String jsonAfter = GSON.toJson(roundTrip.writeJson());

        assertCondition("Binary: JSON matches", jsonBefore.equals(jsonAfter));
        assertCondition("Binary: context has bindings", decoded.context().size() > 0);
        assertCondition("Binary: UUID preserved", expectedUuid.equals(TagUtil.getUUID(roundTrip, "uuid")));
        assertCondition("Binary: nested list size",
                roundTrip.get("tick_history") instanceof ListTag list && list.size() == 3);
    }

    private static void assertCondition(String testName, boolean condition) {
        if (condition) {
            System.out.println("[PASS] " + testName);
        } else {
            System.err.println("[FAIL] " + testName);
        }
    }
}
