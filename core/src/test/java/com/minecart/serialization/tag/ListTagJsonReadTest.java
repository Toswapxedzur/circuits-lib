package com.minecart.serialization.tag;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListTagJsonReadTest {

    @Test
    void readJsonAggregatesFailuresWithSuppressed() {
        JsonArray arr = new JsonArray();
        arr.add(new JsonPrimitive(1.0));
        arr.add(JsonNull.INSTANCE);
        arr.add(new JsonPrimitive(2.0));

        ListTag list = new ListTag();
        IOException ex = assertThrows(IOException.class, () -> list.readJson(arr));
        assertTrue(ex.getMessage().contains("Failed to parse"));
        assertEquals(1, ex.getSuppressed().length);
        assertTrue(ex.getSuppressed()[0].getMessage().contains("index 1"));
    }

    @Test
    void readJsonSuccessWhenAllElementsValid() throws IOException {
        JsonArray arr = new JsonArray();
        arr.add(new JsonPrimitive(1.0));
        arr.add(new JsonPrimitive(2.0));

        ListTag list = new ListTag();
        list.readJson(arr);
        assertEquals(2, list.size());
    }
}
