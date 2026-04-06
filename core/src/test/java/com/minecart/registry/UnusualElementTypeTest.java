package com.minecart.registry;

import com.minecart.logic.CircuitElement;
import com.minecart.logic.CircuitNode;
import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnusualElementTypeTest {

    @Test
    void unusualDirectCreateThrows() {
        ServerWorld w = new ServerLevel().createWorld();
        assertThrows(IllegalStateException.class, () -> AllComponents.CIRCUIT_NODE.create(w));
        assertThrows(IllegalStateException.class, () -> AllComponents.CIRCUIT_EDGE.create(w));
    }

    @Test
    void unusualWorldApisThrow() {
        ServerWorld w = new ServerLevel().createWorld();
        CircuitNode n1 = w.createNode(AllComponents.CONNECTION);
        CircuitNode n2 = w.createNode(AllComponents.CONNECTION);
        assertThrows(IllegalStateException.class, () -> w.createNode(AllComponents.CIRCUIT_NODE));
        assertThrows(IllegalStateException.class, () -> w.connect(AllComponents.CIRCUIT_EDGE, n1, n2));
    }

    @Test
    void deserializeAllowsUnusual() {
        ServerWorld w = new ServerLevel().createWorld();
        CircuitNode node = AllComponents.CIRCUIT_NODE.create(w, true);
        var tag = CircuitElement.serialize(node);
        assertDoesNotThrow(() -> CircuitElement.deserialize(tag, w));
    }

    @Test
    void connectionIsNotUnusual() {
        assertTrue(!AllComponents.CONNECTION.isUnusual());
        assertTrue(AllComponents.CIRCUIT_NODE.isUnusual());
        assertTrue(AllComponents.CIRCUIT_EDGE.isUnusual());
    }
}
