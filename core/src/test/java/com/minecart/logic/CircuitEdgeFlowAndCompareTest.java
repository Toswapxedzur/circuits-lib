package com.minecart.logic;

import com.minecart.misc.CurrentFlow;
import com.minecart.registry.AllComponents;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CircuitEdgeFlowAndCompareTest {

    /** B10: a numerically-negligible solver current must read as {@link CurrentFlow#NO}, not IN/OUT. */
    @Test
    void flowDirection_treatsNegligibleCurrentAsNoFlow() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        CircuitNode a = w.createNode(AllComponents.CONNECTION);
        CircuitNode b = w.createNode(AllComponents.CONNECTION);
        CircuitEdge edge = w.connect(AllComponents.RESISTOR, a, b);

        // Below the epsilon threshold: an exact == 0 test would have wrongly reported IN/OUT.
        edge.getCurrent().setValue(1e-15);
        assertEquals(CurrentFlow.NO, edge.flowDirection(a));
        assertEquals(CurrentFlow.NO, edge.flowDirection(b));

        // A real current still resolves direction: positive current flows start(a) -> end(b).
        edge.getCurrent().setValue(0.5);
        assertEquals(CurrentFlow.OUT, edge.flowDirection(a));
        assertEquals(CurrentFlow.IN, edge.flowDirection(b));
    }

    /** B8: compareTo must be ascending by id, consistent with the declared comparator. */
    @Test
    void compareTo_isAscendingById() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        CircuitNode lo = w.createNode(AllComponents.CONNECTION);
        CircuitNode hi = w.createNode(AllComponents.CONNECTION);
        lo.setId(new UUID(0L, 1L));
        hi.setId(new UUID(0L, 2L));

        assertTrue(lo.compareTo(hi) < 0, "smaller id should compare less");
        assertTrue(hi.compareTo(lo) > 0, "larger id should compare greater");
        assertEquals(0, lo.compareTo(lo));
    }
}
