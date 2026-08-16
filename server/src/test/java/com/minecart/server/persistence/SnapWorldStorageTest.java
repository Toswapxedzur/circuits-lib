package com.minecart.server.persistence;

import com.minecart.elements.edge.Resistor;
import com.minecart.foundation.Circuit;
import com.minecart.foundation.GameMode;
import com.minecart.logic.CircuitEdge;
import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;
import com.minecart.snap.AllSnapParts;
import com.minecart.snap.Facing;
import com.minecart.snap.SnapBoard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 1b end-to-end: a snap world's board (not its derived circuit) is what persists, and reloading it
 * restores the board and rebuilds an identical, solvable circuit. Uses the same unit-square loop as
 * {@link com.minecart.snap.SnapBoardSolveTest} (battery + 2 wires + resistor => 2A).
 */
class SnapWorldStorageTest {

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

    private static SnapBoard loopBoard() {
        // Battery on the base + resistor stacked on top = a V/R loop under the real stacking rule.
        SnapBoard board = new SnapBoard(4, 4, 3);
        board.place(AllSnapParts.SNAP_BATTERY, 0, 0, 0, Facing.EAST, 2.0);
        board.place(AllSnapParts.SNAP_RESISTOR, 0, 0, 1, Facing.EAST, 1.0);
        return board;
    }

    @Test
    void snapBoardSurvivesSaveLoadAndStillSolves(@TempDir Path tmp) throws IOException {
        AllSnapParts.init();

        ServerLevel src = new ServerLevel();
        src.setGameMode(GameMode.SNAP_3D);
        ServerWorld w = src.createWorld();
        w.setSnapBoard(loopBoard());

        WorldStorage.save(src, tmp);

        ServerLevel dst = new ServerLevel();
        assertTrue(WorldStorage.load(tmp, dst));
        assertEquals(GameMode.SNAP_3D, dst.getGameMode(), "mode should round-trip");

        ServerWorld dw = (ServerWorld) dst.findWorld(w.getId());
        assertNotNull(dw, "world id should round-trip");
        assertNotNull(dw.getSnapBoard(), "board should be restored");
        assertEquals(2, dw.getSnapBoard().placements().size(), "both parts should round-trip");

        // load() already rebuilt the derived circuit; tick and confirm the physics survived the round-trip.
        dst.tick();
        Resistor resistor = findResistor(dw);
        assertNotNull(resistor, "derived resistor should exist after reload");
        assertEquals(2.0, Math.abs(resistor.getCurrent().getValue()), 1e-6,
                "reloaded snap loop should still carry 2A");
    }

    @Test
    void freshSnapSaveLoadsWithSeededBoard(@TempDir Path tmp) throws IOException {
        WorldStorage.writeEmpty(tmp, java.util.UUID.randomUUID(), GameMode.SNAP_3D);

        ServerLevel level = new ServerLevel();
        assertTrue(WorldStorage.load(tmp, level));
        assertEquals(GameMode.SNAP_3D, level.getGameMode());

        ServerWorld world = (ServerWorld) level.getWorlds().iterator().next();
        assertNotNull(world.getSnapBoard(), "a fresh snap world should have a board");
        assertEquals(SnapBoard.DEFAULT_WIDTH, world.getSnapBoard().width());
        // TEMPORARY: fresh snap saves currently seed the demo scene (4 parts) so the 3D view is populated.
        assertEquals(4, world.getSnapBoard().placements().size(), "demo scene should round-trip");
    }

    @Test
    void createDefaultBoardIsEmpty() {
        assertEquals(0, SnapBoard.createDefault().placements().size(), "createDefault() must stay empty");
    }
}
