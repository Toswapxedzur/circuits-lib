package com.minecart.server.handler;

import com.minecart.foundation.World;
import com.minecart.logic.CircuitEdge;
import com.minecart.logic.CircuitNode;
import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;
import com.minecart.protocol.payload.PayloadHandler;
import com.minecart.protocol.payload.client.ConnectEdgePayload;
import com.minecart.registry.CircuitElementRegistry;
import com.minecart.registry.CircuitElementType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Server-side handler for {@link ConnectEdgePayload}: locates the two endpoint nodes by id (any circuit in
 * the world, including ports of components) and connects them with a new {@link CircuitEdge} of the
 * requested type. No-op if either id is unknown, the type is unusable, or the connection is otherwise
 * rejected by the world.
 */
public final class ConnectEdgeHandler implements PayloadHandler<ConnectEdgePayload> {

    private static final Logger log = LoggerFactory.getLogger(ConnectEdgeHandler.class);

    private final ServerLevel level;

    public ConnectEdgeHandler(ServerLevel level) {
        this.level = Objects.requireNonNull(level, "level");
    }

    @Override
    public void handle(ConnectEdgePayload payload) {
        // The dispatcher already marshals onto the tick thread; apply directly (no second submit hop).
        apply(payload);
    }

    private void apply(ConnectEdgePayload payload) {
        World world = level.findWorld(payload.getWorldId());
        if (!(world instanceof ServerWorld serverWorld)) {
            return;
        }
        CircuitElementType<?> rawType;
        try {
            rawType = CircuitElementRegistry.getType(payload.getElementTypeId());
        } catch (IllegalArgumentException ex) {
            log.debug("connect-edge: unknown element type '{}', dropping", payload.getElementTypeId());
            return;
        }
        if (rawType.isUnusual()) {
            return;
        }
        CircuitNode start = ElementLookup.findNode(serverWorld, payload.getStartNodeId());
        CircuitNode end = ElementLookup.findNode(serverWorld, payload.getEndNodeId());
        if (start == null || end == null || start == end) {
            return;
        }
        // Ports of a component are the only nodes external wires are allowed to attach to. Reject
        // anything else even if a stale or hand-rolled client hands us the UUID of a hidden internal
        // node (e.g. a BJT's centre junction). Without this check, a client could short-circuit a
        // component's private constitutive graph by connecting to its junction, breaking the
        // electrical model.
        if (isExternallyConnectable(start) == false || isExternallyConnectable(end) == false) {
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            CircuitElementType<? extends CircuitEdge> edgeType = (CircuitElementType<? extends CircuitEdge>) rawType;
            serverWorld.connect(edgeType, start, end);
        } catch (ClassCastException | IllegalArgumentException ignored) {
        }
    }

    /**
     * Free-floating nodes (no owning component) are always wireable; a node owned by a component is
     * wireable iff the component itself reports it as a port. Mirrors the renderer's visibility rule
     * (see {@code WorldStage.reconcile}) so the server's enforcement matches what the user can see.
     */
    private static boolean isExternallyConnectable(CircuitNode node) {
        var owner = node.getComponent();
        if (owner == null) {
            return true;
        }
        return owner.isPort(node);
    }
}
