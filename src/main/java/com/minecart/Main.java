package com.minecart;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;
import com.minecart.serialization.tag.DoubleTag;
import com.minecart.serialization.tag.ListTag;
import com.minecart.serialization.tag.TagRegistry;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

public class Main {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static void main(String[] args) {
        System.out.println("=== INITIALIZING TAG SERIALIZATION ENGINE ===\n");

        // 1. Construct the Live Graph Data
        CompoundTag originalData = new CompoundTag();
        UUID componentId = UUID.randomUUID();

        originalData.putString("id", "minecart:battery");
        originalData.putInt("tick_delay", 20);
        originalData.putDouble("voltage", 12.5);
        originalData.putBoolean("is_active", true);
        TagUtil.putUUID(originalData, "uuid", componentId);

        // Test array storage
        ListTag historyList = new ListTag();
        historyList.add(new DoubleTag(11.2));
        historyList.add(new DoubleTag(12.0));
        historyList.add(new DoubleTag(12.5));
        originalData.put("tick_history", historyList);

        try {
            // Simulate saving to a .json file
            JsonElement jsonOutput = originalData.writeJson();
            String jsonFileContent = GSON.toJson(jsonOutput);
            System.out.println("JSON Output:\n" + jsonFileContent);

            // Simulate loading from a .json file
            CompoundTag tag = new CompoundTag();
            tag.readJson(jsonOutput);

            System.out.println(TagUtil.getUUID(tag, "uuid"));

        } catch (IOException e) {
            System.err.println("JSON stream failed: " + e.getMessage());
        }
    }

    private static void assertCondition(String testName, boolean condition) {
        if (condition) {
            System.out.println("[PASS] " + testName);
        } else {
            System.err.println("[FAIL] " + testName);
        }
    }
}