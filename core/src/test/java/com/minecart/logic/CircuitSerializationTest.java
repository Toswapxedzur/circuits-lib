package com.minecart.logic;

import com.minecart.registry.AllComponents;
import com.minecart.serialization.tag.CompoundTag;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CircuitSerializationTest {

    @Test
    void saveAndLoadRoundTrip() throws IOException {
        ServerLevel level = new ServerLevel();
        ServerWorld w1 = level.createWorld();
        CircuitNode n1 = w1.createNode(AllComponents.CONNECTION);
        CircuitNode n2 = w1.createNode(AllComponents.CONNECTION);
        CircuitEdge edge = w1.connect(AllComponents.RESISTOR, n1, n2);
        assertNotNull(edge);

        Circuit original = n1.getCircuit();
        assertEquals(2, original.nodes().size());
        assertEquals(1, original.edges().size());

        CompoundTag tag = new CompoundTag();
        original.save(tag);

        ServerWorld w2 = level.createWorld();
        ServerCircuit loaded = ServerCircuit.loadFromTag(w2, tag);

        assertEquals(original.getId(), loaded.getId());
        assertEquals(2, loaded.nodes().size());
        assertEquals(1, loaded.edges().size());
        CircuitEdge e2 = loaded.edges().iterator().next();
        assertTrue(e2.isConnected());
        assertNotNull(e2.getStart());
        assertNotNull(e2.getEnd());
    }
}
