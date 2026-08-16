package com.minecart.server.integrated;

import com.minecart.foundation.World;
import com.minecart.logic.CircuitNode;
import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;
import com.minecart.registry.AllComponents;
import com.minecart.server.persistence.WorldStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * H11 regression: {@link IntegratedServer#saveAndStop()} must stop the tick/drag pumps BEFORE serializing the
 * live level (so the save doesn't iterate the level's mutable, unsynchronized collections while the tick thread
 * mutates them), and must always run {@link IntegratedServer#stop()} even if the save throws (try/finally).
 *
 * <p>Reproducing the concurrent-mutation crash deterministically is inherently flaky, so this test locks the
 * observable contract instead: after {@code saveAndStop} the server is stopped and the world state was persisted
 * intact and reloads cleanly.
 */
class IntegratedServerSaveAndStopTest {

    @Test
    void saveAndStop_persistsStateAndStops(@TempDir Path tmp) throws Exception {
        AllComponents.init();

        ServerLevel level = new ServerLevel();
        ServerWorld world = level.createWorld();
        CircuitNode a = world.createNode(AllComponents.CONNECTION);
        CircuitNode b = world.createNode(AllComponents.CONNECTION);
        UUID worldId = world.getId();

        IntegratedServer server = new IntegratedServer(level, tmp);
        server.start();
        // start() loads from the (empty) tmp dir -> no-op, keeping the pre-populated world.

        server.saveAndStop();

        assertFalse(server.isStarted(), "server must be stopped after saveAndStop");

        // Reload into a fresh level and confirm the two nodes survived the save.
        ServerLevel reloaded = new ServerLevel();
        WorldStorage.load(tmp, reloaded);
        World reloadedWorld = reloaded.findWorld(worldId);
        assertNotNull(reloadedWorld, "saved world id should round-trip");
        int nodes = reloadedWorld.getCircuits().stream().mapToInt(c -> c.nodes().size()).sum();
        assertEquals(2, nodes, "both placed nodes should be persisted by saveAndStop");
        assertNotNull(a);
        assertNotNull(b);
    }

    @Test
    void saveAndStop_withoutSaveDir_stops() throws Exception {
        AllComponents.init();
        IntegratedServer server = new IntegratedServer(new ServerLevel(), null);
        server.start();

        server.saveAndStop();

        assertFalse(server.isStarted(), "server with no save dir must still stop");
    }
}
