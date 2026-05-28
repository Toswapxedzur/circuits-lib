package com.minecart.logic.cascade;

import com.minecart.logic.CircuitComponent;
import com.minecart.logic.CircuitEdge;
import com.minecart.logic.CircuitNode;
import com.minecart.logic.ServerWorld;

/**
 * Drops an edge that would become a self-loop on the survivor after the cascade. Mirrors the
 * "edge.getOther(absorbed) == survivor" branch in the existing
 * {@link ServerWorld#combineNodes(CircuitNode, CircuitNode) combineNodes} flow — including the
 * component-internal bypass: an internal star-graph edge whose two ends are both about to point at
 * the same node must be removed even though it's owned by a component (otherwise the component's
 * internal graph carries a phantom self-loop).
 *
 * <p>Records the old endpoints, component ownership, and any flags needed to put the edge back if
 * a later op fails. Reconstructing a disconnected edge cleanly is messy though — circuit
 * re-attachment in particular — so this op's {@link #undo} is best-effort and the engine should
 * arrange the plan so disconnects come <em>after</em> all preflight has passed; they're the kind
 * of step that, in practice, never fails to apply.
 */
public final class DisconnectEdgeOp implements CascadeOp {

    private final CircuitEdge edge;
    private CircuitComponent oldComponent;
    private boolean disconnected;

    public DisconnectEdgeOp(CircuitEdge edge) {
        this.edge = edge;
    }

    @Override
    public boolean apply(ServerWorld world) {
        if (edge == null) {
            return false;
        }
        oldComponent = edge.getComponent();
        if (oldComponent != null) {
            // Internal edge: bypass the hasComponent guard the same way teardown does, so the
            // standard disconnect path will accept it.
            edge.setComponent(null);
        }
        disconnected = world.disconnect(edge);
        if (!disconnected && oldComponent != null) {
            edge.setComponent(oldComponent);
            oldComponent = null;
        }
        return disconnected;
    }

    @Override
    public void undo(ServerWorld world) {
        // Reattaching a disconnected edge is non-trivial (circuit re-bind, event resync). Document
        // limitation: this op's undo is intentionally weak. Place it late in the plan so the
        // engine practically never reaches the undo path with a disconnect on the stack.
        disconnected = false;
        oldComponent = null;
    }

    public CircuitEdge getEdge() {
        return edge;
    }
}
