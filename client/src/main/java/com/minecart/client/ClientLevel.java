package com.minecart.client;

import com.minecart.logic.Level;
import com.minecart.logic.World;

/**
 * Client-side view of a level: owns {@link ClientWorld}s that mirror server electrical networks.
 * Future: drive state from server snapshots and optional prediction; packet handling will attach here later.
 */
public class ClientLevel extends Level {

    /**
     * Creates a new client-side electrical network container.
     */
    public ClientWorld createWorld() {
        ClientWorld world = new ClientWorld(this);
        addWorld(world);
        return world;
    }

    public void destroyWorld(World world) {
        removeWorld(world);
    }

    /**
     * Client frame step: visualization and hooks between server updates (not the authoritative simulation).
     */
    public void tick() {
        for (World w : getWorlds()) {
            if (w instanceof ClientWorld cw) {
                cw.tick();
            }
        }
    }
}
