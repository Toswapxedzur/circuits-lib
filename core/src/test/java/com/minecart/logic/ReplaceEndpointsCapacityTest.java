package com.minecart.logic;

import com.minecart.elements.node.Junction;
import com.minecart.registry.AllComponents;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduces M4: repointing an edge onto a capacity-full {@link Junction} must fail loudly and leave
 * the graph consistent, instead of silently setting {@code edge.end = junction} while the junction's
 * connection set never receives the edge (asymmetric adjacency → wrong solves).
 */
class ReplaceEndpointsCapacityTest {

    @Test
    void changeEdgeEndpoint_ontoFullJunction_throwsAndLeavesGraphIntact() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();

        Junction j = (Junction) w.createNode(AllComponents.JUNCTION);
        j.set(0, 1); // capacity: exactly one connection

        CircuitNode a = w.createNode(AllComponents.CONNECTION);
        CircuitEdge e1 = w.connect(AllComponents.RESISTOR, a, j); // fills the junction's one slot

        CircuitNode b = w.createNode(AllComponents.CONNECTION);
        CircuitNode c = w.createNode(AllComponents.CONNECTION);
        CircuitEdge e2 = w.connect(AllComponents.RESISTOR, b, c);

        // Try to move e2's end onto the already-full junction. connectEdge returns false at capacity,
        // so the guarded swap must throw rather than corrupt adjacency.
        assertThrows(IllegalStateException.class, () -> w.changeEdgeEndpoint(e2, b, j));

        // e2 untouched: still b -> c, with both endpoints still referencing it.
        assertSame(b, e2.getStart());
        assertSame(c, e2.getEnd());
        assertTrue(b.getConnection().contains(e2));
        assertTrue(c.getConnection().contains(e2));

        // The junction still holds only its original edge — e2 never leaked into its connection set.
        assertEquals(1, j.getConnection().size());
        assertTrue(j.getConnection().contains(e1));
        assertFalse(j.getConnection().contains(e2));
    }
}
