package com.minecart.server.persistence;

import com.minecart.elements.edge.Diode;
import com.minecart.foundation.Circuit;
import com.minecart.foundation.World;
import com.minecart.logic.CircuitNode;
import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;
import com.minecart.registry.AllComponents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end save/load roundtrip for {@link WorldStorage}: build a level with one circuit, save, load into a fresh
 * level in a different working dir, and assert the structure (world id, circuit id, node count, edge parameters)
 * matches. Also covers {@link WorldStorage#load(Path, ServerLevel)} on an empty directory (returns false, no-op)
 * and {@link WorldStorage#writeEmpty(Path, UUID)} (initial seed produced by world creation).
 * <p>
 * Uses {@link Diode} rather than {@link com.minecart.elements.edge.Resistor} because Diode overrides
 * {@code save()/load()} to persist its info subtag; {@code Resistor} doesn't (yet), so its value would not survive
 * a round-trip.
 */
class WorldStorageTest {

    @Test
    void saveAndLoad_preservesWorldsCircuitsAndElements(@TempDir Path tmp) throws IOException {
        ServerLevel src = new ServerLevel();
        ServerWorld w = src.createWorld();
        CircuitNode n1 = w.createNode(AllComponents.CONNECTION);
        CircuitNode n2 = w.createNode(AllComponents.CONNECTION);
        Diode d = (Diode) w.connect(AllComponents.DIODE, n1, n2);
        d.get().setForwardResistance(2.5);
        d.get().setReverseResistance(1.5e6);

        UUID worldId = w.getId();
        UUID circuitId = w.getCircuits().iterator().next().getId();

        WorldStorage.save(src, tmp);
        assertTrue(Files.exists(tmp.resolve("level.dat")), "level.dat should exist after save");

        ServerLevel dst = new ServerLevel();
        boolean loaded = WorldStorage.load(tmp, dst);
        assertTrue(loaded, "load should report a save was found");

        World dstWorld = dst.findWorld(worldId);
        assertNotNull(dstWorld, "world id should round-trip");
        assertEquals(1, dstWorld.getCircuits().size(), "one circuit expected");
        Circuit dstCircuit = dstWorld.findCircuit(circuitId);
        assertNotNull(dstCircuit, "circuit id should round-trip");
        assertEquals(2, dstCircuit.nodes().size(), "node count should round-trip");
        assertEquals(1, dstCircuit.edges().size(), "edge count should round-trip");
        Diode loadedDiode = (Diode) dstCircuit.edges().iterator().next();
        assertEquals(2.5, loadedDiode.get().getForwardResistance(), 1e-9, "forward resistance should round-trip");
        assertEquals(1.5e6, loadedDiode.get().getReverseResistance(), 1.0, "reverse resistance should round-trip");
    }

    @Test
    void load_returnsFalseWhenNoLevelDat(@TempDir Path tmp) throws IOException {
        ServerLevel level = new ServerLevel();
        boolean loaded = WorldStorage.load(tmp, level);
        assertFalse(loaded, "missing level.dat should be a no-op (returns false)");
        assertEquals(0, level.getWorlds().size(), "level should remain empty");
    }

    @Test
    void writeEmpty_createsValidLoadableSave(@TempDir Path tmp) throws IOException {
        UUID worldId = UUID.randomUUID();
        WorldStorage.writeEmpty(tmp, worldId);
        assertTrue(Files.exists(tmp.resolve("level.dat")));

        ServerLevel level = new ServerLevel();
        assertTrue(WorldStorage.load(tmp, level));
        assertNotNull(level.findWorld(worldId), "world id from writeEmpty should round-trip");
        assertEquals(0, level.findWorld(worldId).getCircuits().size(), "no circuits expected");
    }
}
