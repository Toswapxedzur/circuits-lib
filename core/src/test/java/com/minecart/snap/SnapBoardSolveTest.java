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
 * Phase 1 proof that snap mode "shares the same basic logic": a board built entirely from snap parts,
 * derived into a {@link ServerWorld} via {@link SnapBoard#rebuild}, is solved by the exact same engine as
 * a 2D world. A unit square of posts carries a 2V battery, two ideal wires, and a 1Ω resistor in one
 * loop, so the steady current through the resistor must be V/R = 2A.
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
    void snapLoopSolvesToVOverR() {
        AllSnapParts.init();

        //   D(0,1) --wire-- C(1,1)
        //     |               |
        //   wire           resistor(1Ω)
        //     |               |
        //   A(0,0) --batt--- B(1,0)      (battery 2V: A->B)
        SnapBoard board = new SnapBoard(1, 1, 1);
        assertTrue(board.place(AllSnapParts.SNAP_BATTERY, 0, 0, 0, Facing.EAST, 2.0));   // A -> B
        assertTrue(board.place(AllSnapParts.SNAP_WIRE, 1, 0, 0, Facing.NORTH));          // B -> C
        assertTrue(board.place(AllSnapParts.SNAP_RESISTOR, 1, 1, 0, Facing.WEST, 1.0));  // C -> D
        assertTrue(board.place(AllSnapParts.SNAP_WIRE, 0, 1, 0, Facing.SOUTH));          // D -> A

        ServerLevel level = new ServerLevel();
        ServerWorld world = level.createWorld();
        board.rebuild(world);

        // Two wires merge the four posts into two electrical nodes; only the battery and resistor remain
        // as edges. (A -A/D- rep X, B -B/C- rep Y  =>  X --battery--> Y --resistor--> X.)
        int nodes = 0, edges = 0;
        for (Circuit c : world.getCircuits()) {
            nodes += c.nodes().size();
            edges += c.edges().size();
        }
        assertEquals(2, nodes, "the two wires should collapse four posts into two shared nodes");
        assertEquals(2, edges, "only the battery and resistor are device edges");

        level.tick();

        Resistor resistor = findResistor(world);
        assertNotNull(resistor, "resistor should exist in the derived circuit");
        assertEquals(2.0, Math.abs(resistor.getCurrent().getValue()), 1e-6,
                "loop current through the 1Ohm resistor from a 2V source should be 2A");
    }

    @Test
    void placementValidationRejectsOffBoardAndDuplicateEdges() {
        AllSnapParts.init();
        SnapBoard board = new SnapBoard(1, 1, 1);

        // Off-board: origin (1,0) facing EAST -> far post (2,0) exceeds width=1.
        assertFalse(board.canPlace(new SnapPlacement(AllSnapParts.SNAP_WIRE, 1, 0, 0, Facing.EAST)));
        assertFalse(board.place(AllSnapParts.SNAP_WIRE, 1, 0, 0, Facing.EAST));

        // First placement on an edge succeeds; a second part on the same two posts is rejected...
        assertTrue(board.place(AllSnapParts.SNAP_WIRE, 0, 0, 0, Facing.EAST));            // A-B
        assertFalse(board.place(AllSnapParts.SNAP_RESISTOR, 0, 0, 0, Facing.EAST));       // same edge A-B
        // ...even when described from the far end (B->A is the same undirected edge).
        assertFalse(board.place(AllSnapParts.SNAP_RESISTOR, 1, 0, 0, Facing.WEST));       // B-A == A-B
    }

    @Test
    void resizeGrowsButNeverShrinks() {
        SnapBoard board = new SnapBoard(2, 2, 1);
        board.resize(4, 3);
        assertEquals(4, board.width());
        assertEquals(3, board.height());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> board.resize(1, 3));
    }
}
