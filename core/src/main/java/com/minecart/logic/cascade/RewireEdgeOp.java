package com.minecart.logic.cascade;

import com.minecart.logic.CircuitEdge;
import com.minecart.logic.CircuitNode;
import com.minecart.logic.ServerWorld;

/**
 * Repoints a {@link CircuitEdge} to new endpoints via {@link ServerWorld#changeEdgeEndpoint}. Holds
 * the previous {@code start} / {@code end} so {@link #undo} can put them back.
 *
 * <p>Used by the cascade engine to walk every edge incident to the absorbed node and redirect it
 * onto the survivor. The engine pre-filters self-loops out and uses {@link DisconnectEdgeOp}
 * instead for those — this op is for legitimate rewires only.
 */
public final class RewireEdgeOp implements CascadeOp {

    private final CircuitEdge edge;
    private final CircuitNode newStart;
    private final CircuitNode newEnd;
    private CircuitNode oldStart;
    private CircuitNode oldEnd;

    public RewireEdgeOp(CircuitEdge edge, CircuitNode newStart, CircuitNode newEnd) {
        this.edge = edge;
        this.newStart = newStart;
        this.newEnd = newEnd;
    }

    @Override
    public boolean apply(ServerWorld world) {
        if (edge == null || newStart == null || newEnd == null) {
            return false;
        }
        oldStart = edge.getStart();
        oldEnd = edge.getEnd();
        if (oldStart == newStart && oldEnd == newEnd) {
            return true; // no-op
        }
        return world.changeEdgeEndpoint(edge, newStart, newEnd);
    }

    @Override
    public void undo(ServerWorld world) {
        if (oldStart == null || oldEnd == null) {
            return;
        }
        world.changeEdgeEndpoint(edge, oldStart, oldEnd);
    }

    public CircuitEdge getEdge() {
        return edge;
    }

    public CircuitNode getNewStart() {
        return newStart;
    }

    public CircuitNode getNewEnd() {
        return newEnd;
    }
}
