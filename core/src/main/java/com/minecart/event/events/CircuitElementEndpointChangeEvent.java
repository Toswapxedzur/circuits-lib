package com.minecart.event.events;

import com.minecart.foundation.World;
import com.minecart.logic.CircuitEdge;
import com.minecart.logic.CircuitNode;

/**
 * Fired by {@link com.minecart.logic.ServerWorld#changeEdgeEndpoint} after an edge's {@code start} and/or
 * {@code end} pointer has been swapped to a different node, so the standard incremental sync pipeline
 * picks the change up and replicates the new {@code edge.start} / {@code edge.end} to every client mirror.
 *
 * <p>Endpoint mutations don't go through {@link com.minecart.logic.CircuitEdge#connect} /
 * {@link com.minecart.logic.CircuitEdge#disconnect} (those have a {@code hasComponent()} guard that's
 * intentionally locked against user-driven topology edits and would refuse internal-port edges), so the
 * sync listener wouldn't otherwise know the edge has been touched. The {@code CircuitElementListener}
 * subscribes here and marks the edge as {@code changed} for this tick — the resulting CHANGE op carries
 * the updated endpoint UUIDs in its serialized data, and the client's {@code CircuitEdge.load} resync
 * branch detaches/reattaches the edge to match.
 *
 * <p>Not cancellable: by the time this event fires, the swap on the authoritative side is already done.
 * Listeners only observe.
 */
public class CircuitElementEndpointChangeEvent extends Event {

    private final World world;
    private final CircuitEdge edge;
    private final CircuitNode oldStart;
    private final CircuitNode oldEnd;
    private final CircuitNode newStart;
    private final CircuitNode newEnd;

    public CircuitElementEndpointChangeEvent(World world, CircuitEdge edge,
                                             CircuitNode oldStart, CircuitNode oldEnd,
                                             CircuitNode newStart, CircuitNode newEnd) {
        this.world = world;
        this.edge = edge;
        this.oldStart = oldStart;
        this.oldEnd = oldEnd;
        this.newStart = newStart;
        this.newEnd = newEnd;
    }

    public World getWorld() {
        return world;
    }

    public CircuitEdge getEdge() {
        return edge;
    }

    public CircuitNode getOldStart() {
        return oldStart;
    }

    public CircuitNode getOldEnd() {
        return oldEnd;
    }

    public CircuitNode getNewStart() {
        return newStart;
    }

    public CircuitNode getNewEnd() {
        return newEnd;
    }
}
