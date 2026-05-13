package com.minecart.client.logic;

import com.minecart.client.events.ClientTickEvent;
import com.minecart.foundation.Level;
import com.minecart.foundation.World;

import java.util.UUID;

/**
 * Client-side view of a level: owns {@link ClientWorld}s that mirror server electrical networks.
 * Future: drive state from server snapshots and optional prediction; packet handling will attach here later.
 */
public class ClientLevel extends Level {

    protected final ClientTickEvent.Level preTick = new ClientTickEvent.Level(ClientTickEvent.Phase.PRE, this);
    protected final ClientTickEvent.Level postTick = new ClientTickEvent.Level(ClientTickEvent.Phase.POST, this);

    /**
     * Monotonically increasing counter bumped whenever the world set or any world's {@link World#getName() name}
     * changes. UI code holding a "last seen" snapshot can compare against {@link #worldsRevision()} to detect
     * whether a refresh is needed without subscribing to events or polling the worlds collection contents.
     *
     * <p>Plain {@code int} is safe here because all mutations go through the render thread (Netty I/O threads
     * forward via {@code level.submit(...)}).
     */
    private int worldsRevision;

    /**
     * @return the current revision number for the worlds set + per-world names. Compare against a cached value
     *         to cheaply decide whether downstream UI needs to rebuild.
     */
    public int worldsRevision() {
        return worldsRevision;
    }

    /**
     * Updates a world's name and bumps {@link #worldsRevision()} so observers (e.g. the editor's world dropdown)
     * notice the change. No-op if the world is missing, the new name is null/empty, or the name is unchanged.
     */
    public boolean renameWorld(UUID worldId, String newName) {
        if (newName == null || newName.isEmpty()) return false;
        World w = findWorld(worldId);
        if (w == null) return false;
        if (newName.equals(w.getName())) return false;
        w.setName(newName);
        worldsRevision++;
        return true;
    }

    @Override
    protected void addWorld(World world) {
        super.addWorld(world);
        worldsRevision++;
    }

    @Override
    protected void removeWorld(World world) {
        super.removeWorld(world);
        worldsRevision++;
    }

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
     * Finds a {@link ClientWorld} by {@link World#getId()}, or creates one with that id.
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
        init();
        post(preTick);
        for (World w : getWorlds()) {
            if (w instanceof ClientWorld cw) {
                cw.tick();
            }
        }
        post(postTick);
    }
}
