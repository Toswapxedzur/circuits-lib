package com.minecart.client.logic;

import com.minecart.logic.Level;
import com.minecart.logic.World;

import java.util.UUID;

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
     * Finds a {@link ClientWorld} by {@link com.minecart.logic.World#getId()}, or creates one with that id.
     * When {@code worldId} is {@code null}, uses the sole existing world if there is exactly one, otherwise creates a new world.
     */
    public ClientWorld getOrCreateWorld(UUID worldId) {
        if (worldId != null) {
            World w = findWorld(worldId);
            if (w instanceof ClientWorld cw) {
                return cw;
            }
            ClientWorld cw = new ClientWorld(this, worldId);
            addWorld(cw);
            return cw;
        }
        if (getWorlds().size() == 1) {
            World only = getWorlds().iterator().next();
            if (only instanceof ClientWorld cw) {
                return cw;
            }
        }
        ClientWorld cw = createWorld();
        return cw;
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
