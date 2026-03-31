package com.minecart.client;

import com.minecart.logic.Circuit;

import java.util.UUID;

/**
 * Client-side mirror of a circuit graph (nodes, edges, components) without server-side solving.
 * Future: reconcile with server snapshots and interpolation; network payloads will be applied here later.
 */
public class ClientCircuit extends Circuit {

    protected ClientWorld world;

    public ClientCircuit() {
        super(UUID.randomUUID());
    }

    public ClientCircuit(UUID id) {
        super(id);
    }

    public ClientWorld getWorld() {
        return world;
    }

    public void setWorld(ClientWorld world) {
        this.world = world;
    }

    /**
     * Called between server updates for client-side smoothing or UI (no solver here).
     */
    public void clientTick() {
    }
}
