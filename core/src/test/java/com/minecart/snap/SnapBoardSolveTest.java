package com.minecart.snap;

import com.minecart.elements.edge.Resistor;
import com.minecart.foundation.Circuit;
import com.minecart.logic.CircuitEdge;
import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Snap mode reuses the same solver, and parts connect by STACKING (real Snap-Circuit rule): a battery on
 * the base with a resistor stacked directly on top shares the two bump columns, forming a V/R loop.
 */
class SnapBoardSolveTest {

    private static Resistor findResistor(ServerWorld world) {
        for (Circuit c : world.getCircuits()) {
            for (CircuitEdge e : c.edges()) {
                if (e instanceof Resistor r) {
                    return r;
                }
            }
        }
        return null;
    }

    @Test
    void stackedBatteryResistorSolvesToVOverR() {
        AllSnapParts.init();
        SnapBoard board = new SnapBoard(4, 4, 3);
        assertTrue(board.place(AllSnapParts.SNAP_BATTERY, 0, 0, 0, Facing.EAST, 2.0));   // base: A(0,0)-B(1,0)
        assertTrue(board.place(AllSnapParts.SNAP_RESISTOR, 0, 0, 1, Facing.EAST, 1.0));  // stacked on top

        ServerLevel level = new ServerLevel();
        ServerWorld world = level.createWorld();
        board.rebuild(world);

        // Two bump columns (0,0) and (1,0) become two nodes; battery + resistor are the two edges.
        int nodes = 0, edges = 0;
        for (Circuit c : world.getCircuits()) {
            nodes += c.nodes().size();
            edges += c.edges().size();
        }
        assertEquals(2, nodes, "two bump columns -> two nodes (stacking connects them)");
        assertEquals(2, edges, "battery + resistor");

        level.tick();
        Resistor resistor = findResistor(world);
        assertNotNull(resistor);
        assertEquals(2.0, Math.abs(resistor.getCurrent().getValue()), 1e-6, "2V across 1Ohm -> 2A");
    }

    @Test
    void twoComponentsCannotShareABumpAtTheSameLevel() {
        AllSnapParts.init();
        SnapBoard board = new SnapBoard(4, 4, 3);
        assertTrue(board.place(AllSnapParts.SNAP_WIRE, 0, 0, 0, Facing.EAST));           // claims (0,0,0),(1,0,0)
        // A resistor whose terminal also lands on bump (1,0) at level 0 must be rejected.
        assertFalse(board.canPlace(new SnapPlacement(AllSnapParts.SNAP_RESISTOR, 1, 0, 0, Facing.EAST)));
    }

    @Test
    void stackingRequiresSupportBelow() {
        AllSnapParts.init();
        SnapBoard board = new SnapBoard(4, 4, 3);
        // Nothing below -> a level-1 part is unsupported.
        assertFalse(board.canPlace(new SnapPlacement(AllSnapParts.SNAP_WIRE, 0, 0, 1, Facing.EAST)));
        // Put a battery on the base; now both bumps are supported for a stacked part.
        assertTrue(board.place(AllSnapParts.SNAP_BATTERY, 0, 0, 0, Facing.EAST, 2.0));
        assertTrue(board.canPlace(new SnapPlacement(AllSnapParts.SNAP_WIRE, 0, 0, 1, Facing.EAST)));
    }

    @Test
    void offBoardRejected() {
        AllSnapParts.init();
        SnapBoard board = new SnapBoard(1, 1, 2);
        // Origin (1,0) facing EAST -> far post (2,0) exceeds width 1.
        assertFalse(board.canPlace(new SnapPlacement(AllSnapParts.SNAP_WIRE, 1, 0, 0, Facing.EAST)));
    }
}
