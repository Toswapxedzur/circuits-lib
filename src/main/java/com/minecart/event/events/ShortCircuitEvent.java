package com.minecart.event.events;

import com.minecart.logic.CircuitEdge;
import com.minecart.logic.ServerCircuit;
import com.minecart.logic.ServerWorld;

import java.util.Collections;
import java.util.List;

/**
 * Could listen to the event through Level, ande react to it,
 * for example break the circuit when short circuit happens.
 * <p>Could be caused by absurdly large current generated through normal means (normal resistance but with large voltage)
 */
public class ShortCircuitEvent extends Event {

    private final ServerWorld world;
    private final List<CircuitEdge> edges;

    public ShortCircuitEvent(ServerWorld world, List<CircuitEdge> edges) {
        this.world = world;
        this.edges = edges;
    }

    public ServerWorld getWorld() {
        return world;
    }

    /**
     * @return An unmodifiable list of the edges involved in the short circuit path.
     */
    public List<CircuitEdge> getEdges() {
        return Collections.unmodifiableList(edges);
    }
}