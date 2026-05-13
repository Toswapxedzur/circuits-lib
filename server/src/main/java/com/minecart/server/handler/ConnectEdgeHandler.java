package com.minecart.server.handler;

import com.minecart.foundation.Circuit;
import com.minecart.foundation.World;
import com.minecart.logic.CircuitEdge;
import com.minecart.logic.CircuitNode;
import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;
import com.minecart.protocol.payload.PayloadHandler;
import com.minecart.protocol.payload.client.ConnectEdgePayload;
import com.minecart.registry.CircuitElementRegistry;
import com.minecart.registry.CircuitElementType;

import java.util.Objects;
import java.util.UUID;

/**
 * Server-side handler for {@link ConnectEdgePayload}: locates the two endpoint nodes by id (any circuit in
 * the world, including ports of components) and connects them with a new {@link CircuitEdge} of the
 * requested type. No-op if either id is unknown, the type is unusable, or the connection is otherwise
 * rejected by the world.
 */
public final class ConnectEdgeHandler implements PayloadHandler<ConnectEdgePayload> {

    private final ServerLevel level;

    public ConnectEdgeHandler(ServerLevel level) {
        this.level = Objects.requireNonNull(level, "level");
    }

    @Override
    public void handle(ConnectEdgePayload payload) {
        level.submit(() -> apply(payload));
    }

    private void apply(ConnectEdgePayload payload) {
        World world = level.findWorld(payload.getWorldId());
        if (!(world instanceof ServerWorld serverWorld)) {
            return;
        }
        CircuitElementType<?> rawType = CircuitElementRegistry.getType(payload.getElementTypeId());
        if (rawType == null || rawType.isUnusual()) {
            return;
        }
        CircuitNode start = findNode(serverWorld, payload.getStartNodeId());
        CircuitNode end = findNode(serverWorld, payload.getEndNodeId());
        if (start == null || end == null || start == end) {
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            CircuitElementType<? extends CircuitEdge> edgeType = (CircuitElementType<? extends CircuitEdge>) rawType;
            serverWorld.connect(edgeType, start, end);
        } catch (ClassCastException | IllegalArgumentException ignored) {
        }
    }

    private static CircuitNode findNode(ServerWorld world, UUID id) {
        if (id == null) {
            return null;
        }
        for (Circuit circuit : world.getCircuits()) {
            CircuitNode n = circuit.findNode(id);
            if (n != null) {
                return n;
            }
        }
        return null;
    }
}
