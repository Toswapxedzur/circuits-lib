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
 * via {@link #getDragAggregator()} and resolved as a unified physics solve. Resolution happens at
 * the start of every {@link #tick()} (as a safety net for headless callers / tests), and also at
 * the cadence of an optional high-frequency "drag pump" the runtime layer can attach
 * (see {@link com.minecart.server.network.LevelPumps}) so drag responsiveness isn't capped by the
 * main tick rate. The aggregator's buffer drains on every flush, so the two flush sites coexist
 * without double-applying gestures.
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
     * Ensures the level always has at least one world for clients to select. Returns an existing
     * first world when present, otherwise creates and names a default one.
     */
    public ServerWorld ensureDefaultWorld() {
        for (World world : getWorlds()) {
            if (world instanceof ServerWorld sw) {
                return sw;
            }
        }
        ServerWorld world = createWorld();
        world.setName("World 1");
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
     * Safely submit an action to the global simulation queue. Actions drain on the worker thread
     * via {@link #drainActionQueue()} — that's the main tick body's first step, and is also called
     * by the high-frequency drag pump so streaming drag enqueues land within ~one drag-pump period
     * rather than waiting for the next main tick.
     */
    public void submit(Runnable action) {
        actionQueue.offer(action);
    }

    /**
     * Drains the action queue on the calling thread. Safe to call from any thread that owns
     * exclusive write access to the level (in practice: the single worker thread that also runs
     * {@link #tick()}). The {@link com.minecart.server.network.LevelPumps drag pump} invokes this
     * between main ticks so {@code level.submit(...)} actions don't pile up for up to a full tick
     * before drag gestures get enqueued into the aggregator.
     */
    public void drainActionQueue() {
        while (!actionQueue.isEmpty()) {
            Runnable action = actionQueue.poll();
            if (action != null) {
                action.run();
            }
        }
    }

    /**
     * The single global tick method.
     *
     * <p>Phases:
     * <ol>
     *   <li>{@code preTick} event fires.</li>
     *   <li>Action queue is drained — incoming-payload handlers' {@code level.submit}-ed actions
     *       run here; these populate the drag aggregator's per-tick buffer and apply discrete
     *       mutations (panel saves, combine cascades, scroll rotations, etc.). The drag pump may
     *       have already drained queue work since the previous tick; the drain here just catches
     *       anything that landed in the gap between the last pump and this tick.</li>
     *   <li>{@link DragAggregator#flush} resolves any drag gestures still buffered (typically
     *       nothing, since the fast drag pump already flushes them at high cadence). Kept here as
     *       a safety net so headless callers that drive {@code tick()} directly without a pump
     *       still see drag resolution.</li>
     *   <li>Each world's per-tick simulation runs.</li>
     *   <li>{@code postTick} fires.</li>
     * </ol>
     */
    public void tick() {
        init();
        post(preTick);
        drainActionQueue();

        dragAggregator.flush(this);

        for (World world : getWorlds()) {
            if (world instanceof ServerWorld sw) {
                sw.tick();
            }
        }
        post(postTick);
    }
}
