package com.minecart.client.logic;

import com.minecart.logic.Circuit;
import com.minecart.logic.World;
import com.minecart.serialization.tag.CompoundTag;

import java.util.UUID;

/**
 * Client-side mirror of a circuit graph (nodes, edges, components) without server-side solving.
 * Future: reconcile with server snapshots and interpolation; network payloads will be applied here later.
 */
public class ClientCircuit extends Circuit {

    public ClientCircuit() {
        super(UUID.randomUUID());
    }

    public ClientCircuit(UUID id) {
        super(id);
    }

    @Override
    public ClientWorld getWorld() {
        World w = super.getWorld();
        if (w != null && !(w instanceof ClientWorld)) {
            throw new IllegalStateException("ClientCircuit is bound to a non-client world: " + w.getClass().getName());
        }
        return (ClientWorld) w;
    }

    @Override
    public void setWorld(World world) {
        if (world != null && !(world instanceof ClientWorld)) {
            throw new IllegalArgumentException("ClientCircuit requires ClientWorld");
        }
        super.setWorld(world);
    }

    public void setWorld(ClientWorld world) {
        super.setWorld(world);
    }

    @Override
    public void load(World world, CompoundTag tag) {
        if (world != null && !(world instanceof ClientWorld)) {
            throw new IllegalArgumentException("ClientCircuit requires ClientWorld for load");
        }
        super.load(world, tag);
    }

    /**
     * Called between server updates for client-side smoothing or UI (no solver here).
     */
    public void clientTick() {
    }
}
