package com.minecart.server.handler;

import com.minecart.foundation.Circuit;
import com.minecart.logic.CircuitNode;
import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;
import com.minecart.protocol.payload.client.PlaceNodePayload;
import com.minecart.registry.AllComponents;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PlaceNodeHandlerTest {

    private static int nodeCount(ServerWorld world) {
        int n = 0;
        for (Circuit c : world.getCircuits()) {
            n += c.nodes().size();
        }
        return n;
    }

    /**
     * B4 regression: an unknown element-type id must be a silent no-op. Previously the {@code rawType == null}
     * guard was dead because {@link com.minecart.registry.CircuitElementRegistry#getType} throws
     * {@link IllegalArgumentException} on an unknown id; with {@code handle} now applying synchronously
     * (R1 threading change), that exception would propagate out of {@code handle} and, in production, abort
     * the tick-thread drain loop. The handler must swallow it and drop the payload instead.
     */
    @Test
    void unknownElementType_isSilentNoOp() {
        // Ensure the registry is initialised so a *valid* id would resolve — proves the no-op is due to the
        // unknown id, not an empty registry.
        AllComponents.init();
        ServerLevel level = new ServerLevel();
        ServerWorld world = level.createWorld();

        PlaceNodeHandler handler = new PlaceNodeHandler(level);
        PlaceNodePayload payload = new PlaceNodePayload(
                world.getId(), "definitely.not.a.registered.type", 3.0, 4.0);

        assertDoesNotThrow(() -> handler.handle(payload),
                "unknown element type must be dropped silently, not thrown");
        assertEquals(0, nodeCount(world), "no node should be created for an unknown element type");
    }

    /**
     * R1 regression: {@code handle} applies on the calling (dispatcher) thread directly — no extra
     * {@code level.submit} hop — so a valid placement is visible immediately without draining the action queue.
     */
    @Test
    void validPlacement_appliesSynchronously() {
        AllComponents.init();
        ServerLevel level = new ServerLevel();
        ServerWorld world = level.createWorld();

        PlaceNodeHandler handler = new PlaceNodeHandler(level);
        handler.handle(new PlaceNodePayload(world.getId(), "connection", 5.0, 6.0));

        // No level.tick() / drainActionQueue() between handle and assertion.
        assertEquals(1, nodeCount(world), "valid placement should create exactly one node synchronously");
        CircuitNode placed = world.getCircuits().iterator().next().nodes().iterator().next();
        assertNotNull(placed, "the created node should be present in the world");
    }
}
