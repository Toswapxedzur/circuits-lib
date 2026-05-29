package com.minecart.server.handler;

import com.minecart.foundation.Circuit;
import com.minecart.foundation.World;
import com.minecart.logic.CircuitEdge;
import com.minecart.logic.CircuitElement;
import com.minecart.logic.CircuitNode;
import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;
import com.minecart.protocol.payload.PayloadHandler;
import com.minecart.protocol.payload.client.EdgeEndpointChangePayload;
import com.minecart.variant.info.LockMode;
import com.minecart.variant.info.LockState;

import java.util.Objects;
import java.util.UUID;

/**
 * Server-side handler for {@link EdgeEndpointChangePayload}: locates the edge and the two new endpoint
 * nodes anywhere in the requested world (any circuit, including a component's port nodes) and runs
 * {@link ServerWorld#changeEdgeEndpoint} which handles connection-set bookkeeping, circuit merge/split,
 * sync replication, and rebind events.
 *
 * <p>Silent no-op when any id is unknown, when the requested change would create a self-loop, or when
 * the change is already in effect — the server's authoritative state simply doesn't update, which the
 * client reads as "rejected" (no element-change deltas come back in the next sync).
 */
public final class EdgeEndpointChangeHandler implements PayloadHandler<EdgeEndpointChangePayload> {

    /** Pivot tolerance for {@link CircuitEdge#effectiveLockState(double)}; see MoveElementHandler. */
    private static final double LOCK_EPSILON = 1e-6;

    private final ServerLevel level;

    public EdgeEndpointChangeHandler(ServerLevel level) {
        this.level = Objects.requireNonNull(level, "level");
    }

    @Override
    public void handle(EdgeEndpointChangePayload payload) {
        level.submit(() -> apply(payload));
    }

    private void apply(EdgeEndpointChangePayload payload) {
        World world = level.findWorld(payload.getWorldId());
        if (!(world instanceof ServerWorld serverWorld)) {
            return;
        }
        CircuitEdge edge = findEdge(world, payload.getEdgeId());
        if (edge == null) {
            return;
        }
        CircuitNode newStart = findNode(world, payload.getNewStartNodeId());
        CircuitNode newEnd = findNode(world, payload.getNewEndNodeId());
        if (newStart == null || newEnd == null || newStart == newEnd) {
            return;
        }
        // Phase 1 lock enforcement: a strictly LOCKED edge refuses any topological change. The
        // weaker modes (POSITION_FREE, ROTATION_FREE) are kinematic restrictions that don't have
        // a clean meaning for rewiring, so we only gate on LOCKED for now; this matches the
        // user-visible "the strict lock should actually do something" contract.
        LockState eff = edge.effectiveLockState(LOCK_EPSILON);
        if (eff.mode() == LockMode.LOCKED) {
            // Rebroadcast the edge's authoritative state so any client that already showed the
            // optimistic rewire (preview line, dragged endpoint) snaps back. Notifying both old
            // endpoints in addition to the edge itself covers the case where a port-node mirror's
            // connection set has been optimistically updated client-side.
            level.notifyElementChanged(edge);
            CircuitNode oldStart = edge.getStart();
            CircuitNode oldEnd = edge.getEnd();
            if (oldStart != null) level.notifyElementChanged(oldStart);
            if (oldEnd != null) level.notifyElementChanged(oldEnd);
            return;
        }
        try {
            serverWorld.changeEdgeEndpoint(edge, newStart, newEnd);
        } catch (IllegalArgumentException | IllegalStateException ignored) {
            // Forgiving protocol: ignore stale or malformed requests rather than killing the channel.
        }
    }

    private static CircuitEdge findEdge(World world, UUID id) {
        if (id == null) {
            return null;
        }
        for (Circuit circuit : world.getCircuits()) {
            CircuitElement el = circuit.findEdge(id);
            if (el instanceof CircuitEdge e) {
                return e;
            }
        }
        return null;
    }

    private static CircuitNode findNode(World world, UUID id) {
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
