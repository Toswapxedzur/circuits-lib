package com.minecart.logic;

import com.minecart.event.events.ServerTickEvent;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Server-side level tick: processes deferred actions and ticks every {@link World}.
 */
public class ServerLevel extends Level {

    private final Queue<Runnable> actionQueue = new ConcurrentLinkedQueue<>();

    protected final ServerTickEvent.Level preTick = new ServerTickEvent.Level(ServerTickEvent.Phase.PRE, this);
    protected final ServerTickEvent.Level postTick = new ServerTickEvent.Level(ServerTickEvent.Phase.POST, this);

    /**
     * Creates a new, isolated electrical network.
     */
    public ServerWorld createWorld() {
        ServerWorld world = new ServerWorld(this);
        addWorld(world);
        return world;
    }

    /**
     * Removes an entire electrical network (e.g., if all its blocks are destroyed).
     */
    public void destroyWorld(World world) {
        removeWorld(world);
    }

    /**
     * Safely submit an action to the global simulation queue.
     */
    public void submit(Runnable action) {
        actionQueue.offer(action);
    }

    /**
     * The single global tick method.
     */
    public void tick() {
        post(preTick);
        while (!actionQueue.isEmpty()) {
            Runnable action = actionQueue.poll();
            if (action != null) {
                action.run();
            }
        }

        for (World world : getWorlds()) {
            if (world instanceof ServerWorld sw) {
                sw.tick();
            }
        }
        post(postTick);
    }
}
