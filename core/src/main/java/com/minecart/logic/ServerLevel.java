package com.minecart.logic;

import com.minecart.event.events.ServerTickEvent;
import com.minecart.foundation.Level;
import com.minecart.foundation.World;
import com.minecart.logic.physics.DragAggregator;

import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Server-side level tick: processes deferred actions and ticks every {@link World}.
 *
 * <h2>Per-tick drag aggregator</h2>
 * Streaming drag payloads (move / rotate gestures from multiple concurrent clients) are buffered
 * via {@link #getDragAggregator()} and resolved as a single physics solve at the start of each
 * tick, before the per-world tick. This replaces the older "drag lease" model where each gesture
 * acquired exclusive ownership of the touched elements — see the physics package for the
 * soft-spring / contention-policy details.
 */
public class ServerLevel extends Level {

    private final Queue<Runnable> actionQueue = new ConcurrentLinkedQueue<>();
    private final DragAggregator dragAggregator = new DragAggregator();

    protected final ServerTickEvent.Level preTick = new ServerTickEvent.Level(ServerTickEvent.Phase.PRE, this);
    protected final ServerTickEvent.Level postTick = new ServerTickEvent.Level(ServerTickEvent.Phase.POST, this);

    public DragAggregator getDragAggregator() {
        return dragAggregator;
    }

    /**
     * Creates a new, isolated electrical network.
     */
    public ServerWorld createWorld() {
        ServerWorld world = new ServerWorld(this);
        addWorld(world);
        return world;
    }

    /**
     * Creates a network with a fixed id if none with that id exists yet.
     *
     * @return the existing {@link ServerWorld} with {@code worldId}, or a newly registered one
     */
    public ServerWorld getOrCreateWorld(UUID worldId) {
        if (worldId == null) {
            return createWorld();
        }
        World existing = findWorld(worldId);
        if (existing instanceof ServerWorld sw) {
            return sw;
        }
        ServerWorld world = new ServerWorld(this, worldId);
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
     *
     * <p>Phases:
     * <ol>
     *   <li>{@code preTick} event fires.</li>
     *   <li>Action queue is drained — incoming-payload handlers' {@code level.submit}-ed actions
     *       run here; these populate the drag aggregator's per-tick buffer and apply discrete
     *       mutations (panel saves, combine cascades, scroll rotations, etc.).</li>
     *   <li>{@link DragAggregator#flush} resolves all of this tick's buffered drag streams as a
     *       single unified physics solve, writing back authoritative poses. This runs AFTER the
     *       action queue so discrete mutations land in the world before streaming drags adapt
     *       to them.</li>
     *   <li>Each world's per-tick simulation runs.</li>
     *   <li>{@code postTick} fires.</li>
     * </ol>
     */
    public void tick() {
        init();
        post(preTick);
        while (!actionQueue.isEmpty()) {
            Runnable action = actionQueue.poll();
            if (action != null) {
                action.run();
            }
        }

        dragAggregator.flush(this);

        for (World world : getWorlds()) {
            if (world instanceof ServerWorld sw) {
                sw.tick();
            }
        }
        post(postTick);
    }
}
