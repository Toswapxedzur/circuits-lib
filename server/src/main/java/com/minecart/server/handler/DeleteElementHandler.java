package com.minecart.server.handler;

import com.minecart.foundation.Circuit;
import com.minecart.foundation.World;
import com.minecart.logic.CircuitComponent;
import com.minecart.logic.CircuitElement;
import com.minecart.logic.CircuitNode;
import com.minecart.logic.ServerCircuit;
import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;
import com.minecart.protocol.payload.PayloadHandler;
import com.minecart.protocol.payload.client.DeleteElementPayload;

import java.util.Objects;
import java.util.UUID;

/**
 * Server-side handler for {@link DeleteElementPayload}: removes a free node or a whole component from the
 * world. Edges are positionless so the editor never offers them as drag targets; we silently ignore other
 * kinds to keep the protocol forgiving for misbehaving clients.
 */
public final class DeleteElementHandler implements PayloadHandler<DeleteElementPayload> {

    private final ServerLevel level;

    public DeleteElementHandler(ServerLevel level) {
        this.level = Objects.requireNonNull(level, "level");
    }

    @Override
    public void handle(DeleteElementPayload payload) {
        level.submit(() -> apply(payload));
    }

    private void apply(DeleteElementPayload payload) {
        World world = level.findWorld(payload.getWorldId());
        if (!(world instanceof ServerWorld serverWorld)) {
            return;
        }
        CircuitElement el = findElement(world, payload.getElementId());
        if (el == null) {
            return;
        }
        if (el instanceof CircuitComponent comp) {
            // Component owns its internal nodes; destroy() iterates them and removes via the master circuit.
            Circuit owner = comp.getCircuit();
            if (owner instanceof ServerCircuit sc) {
                comp.destroy(sc);
            }
        } else if (el instanceof CircuitNode node) {
            if (node.getComponent() != null) {
                // Internal port node — only deletable through the parent component to avoid orphaning anchors.
                return;
            }
            serverWorld.destroy(node);
        }
    }

    private static CircuitElement findElement(World world, UUID id) {
        for (Circuit c : world.getCircuits()) {
            CircuitElement el = c.findElement(id);
            if (el != null) {
                return el;
            }
        }
        return null;
    }
}
