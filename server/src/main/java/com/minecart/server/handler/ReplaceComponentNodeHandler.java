package com.minecart.server.handler;

import com.minecart.foundation.Circuit;
import com.minecart.foundation.World;
import com.minecart.logic.CircuitComponent;
import com.minecart.logic.CircuitElement;
import com.minecart.logic.CircuitNode;
import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;
import com.minecart.protocol.payload.PayloadHandler;
import com.minecart.protocol.payload.client.ReplaceComponentNodePayload;
import com.minecart.variant.info.LockMode;
import com.minecart.variant.info.LockState;

import java.util.Objects;
import java.util.UUID;

/**
 * Server-side handler for {@link ReplaceComponentNodePayload}: looks up the component, validates that
 * {@code oldNodeId} is currently registered as a port and that the {@code newNodeId} substitution is
 * type-compatible (a port carries a fixed electrical kind, so swapping in a node of a different
 * registry id would silently change component behaviour), then runs
 * {@link CircuitComponent#replacePort}.
 *
 * <p>Notifies the new and old port nodes via {@link ServerLevel#notifyElementChanged} so the client
 * mirror replicates the freshly mutated {@link com.minecart.misc.CoreStrings#NODE_COMPONENT}
 * back-pointers in the same tick (otherwise hover/drag affordances on the client would still treat the
 * old node as the port until the next state-changing op).
 *
 * <p>Silent no-op on any validation failure; the server simply emits no delta and the client reads
 * that as "rejected".
 */
public final class ReplaceComponentNodeHandler implements PayloadHandler<ReplaceComponentNodePayload> {

    /** Pivot tolerance for {@link CircuitComponent#effectiveLockState(double)}. */
    private static final double LOCK_EPSILON = 1e-6;

    private final ServerLevel level;

    public ReplaceComponentNodeHandler(ServerLevel level) {
        this.level = Objects.requireNonNull(level, "level");
    }

    @Override
    public void handle(ReplaceComponentNodePayload payload) {
        level.submit(() -> apply(payload));
    }

    private void apply(ReplaceComponentNodePayload payload) {
        World world = level.findWorld(payload.getWorldId());
        if (!(world instanceof ServerWorld)) {
            return;
        }
        CircuitComponent comp = findComponent(world, payload.getComponentId());
        if (comp == null) {
            return;
        }
        // Phase 1 lock enforcement: a strictly LOCKED component refuses port swaps. ORIENTED
        // and PIVOTED are kinematic-only restrictions; they don't have a natural meaning
        // for topological port replacement, so we only gate on LOCKED for now.
        LockState eff = comp.effectiveLockState(LOCK_EPSILON);
        if (eff.mode() == LockMode.LOCKED) {
            // Rebroadcast the component (and the two nodes the swap would have touched, when
            // known) so any client that already showed an optimistic port replacement reverts.
            level.notifyElementChanged(comp);
            CircuitNode oldNode = findNode(world, payload.getOldNodeId());
            CircuitNode newNode = findNode(world, payload.getNewNodeId());
            if (oldNode != null) level.notifyElementChanged(oldNode);
            if (newNode != null) level.notifyElementChanged(newNode);
            return;
        }
        CircuitNode oldNode = findNode(world, payload.getOldNodeId());
        CircuitNode newNode = findNode(world, payload.getNewNodeId());
        if (oldNode == null || newNode == null || oldNode == newNode) {
            return;
        }
        int portIndex = comp.portIndexOf(oldNode);
        if (portIndex < 0) {
            return;
        }
        if (!Objects.equals(oldNode.getRegistryTypeId(), newNode.getRegistryTypeId())) {
            return;
        }
        try {
            comp.replacePort(portIndex, oldNode, newNode);
        } catch (IllegalArgumentException | IllegalStateException ignored) {
            return;
        }
        level.notifyElementChanged(newNode);
        level.notifyElementChanged(oldNode);
        level.notifyElementChanged(comp);
    }

    private static CircuitComponent findComponent(World world, UUID id) {
        if (id == null) {
            return null;
        }
        for (Circuit circuit : world.getCircuits()) {
            for (CircuitComponent c : circuit.components()) {
                if (id.equals(c.getId())) {
                    return c;
                }
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
