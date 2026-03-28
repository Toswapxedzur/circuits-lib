package com.minecart.logic;

import com.minecart.event.EventBus;
import com.minecart.event.events.CancellableEvent;
import com.minecart.event.events.Event;
import com.minecart.event.events.ServerTickEvent;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

public class ServerLevel {
    protected double tickRate = 0.05;

    private final Queue<Runnable> actionQueue = new ConcurrentLinkedQueue<>();

    private final EventBus eventBus = new EventBus();

    private final Set<ServerWorld> worlds = new LinkedHashSet<>();

    protected final ServerTickEvent.Level preTick = new ServerTickEvent.Level(ServerTickEvent.Phase.PRE, this);
    protected final ServerTickEvent.Level postTick = new ServerTickEvent.Level(ServerTickEvent.Phase.POST, this);

    /**
     * Creates a new, isolated electrical network.
     */
    public ServerWorld createWorld() {
        ServerWorld world = new ServerWorld(this);
        worlds.add(world);
        return world;
    }

    /**
     * Removes an entire electrical network (e.g., if all its blocks are destroyed)
     */
    public void destroyWorld(ServerWorld world) {
        worlds.remove(world);
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
            if (action != null) action.run();
        }

        for (ServerWorld world : worlds) {
            world.tick();
        }
        post(postTick);
    }

    public EventBus getEventBus() {
        return eventBus;
    }

    public <T extends Event> void register(Class<T> eventClass, Consumer<T> listener) {
        eventBus.register(eventClass, listener);
    }

    public boolean post(Event event) {
        return eventBus.post(event);
    }

    public double getTickRate() {
        return tickRate;
    }

    public void setTickRate(double tickRate) {
        this.tickRate = tickRate;
    }
}
