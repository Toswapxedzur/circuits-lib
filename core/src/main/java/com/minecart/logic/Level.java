package com.minecart.logic;

import com.minecart.event.EventBus;
import com.minecart.event.events.Event;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Holds level-wide state: tick configuration, event bus, and the {@link World}s attached to this level.
 * {@link ServerLevel} adds the global tick loop and deferred actions.
 */
public class Level {

    protected double tickRate = 0.05;

    private final EventBus eventBus = new EventBus();

    private final Set<World> worlds = new LinkedHashSet<>();

    public double getTickRate() {
        return tickRate;
    }

    public void setTickRate(double tickRate) {
        this.tickRate = tickRate;
    }

    public EventBus getEventBus() {
        return eventBus;
    }

    public <T extends Event> void register(Class<T> eventClass, Consumer<T> listener) {
        eventBus.register(eventClass, listener);
    }

    public <T extends Event> void unregister(Class<T> eventClass, Consumer<T> listener) {
        eventBus.unregister(eventClass, listener);
    }

    public boolean post(Event event) {
        return eventBus.post(event);
    }

    /** Worlds registered on this level (e.g. separate electrical networks). */
    public Set<World> getWorlds() {
        return Collections.unmodifiableSet(worlds);
    }

    protected void addWorld(World world) {
        worlds.add(world);
    }

    protected void removeWorld(World world) {
        worlds.remove(world);
    }

    /** Finds a world by {@link World#getId()}. */
    public World findWorld(UUID worldId) {
        if (worldId == null) {
            return null;
        }
        for (World w : worlds) {
            if (worldId.equals(w.getId())) {
                return w;
            }
        }
        return null;
    }

    /**
     * Finds a circuit by {@link Circuit#getId()} across all worlds on this level.
     * Used when an action payload omits world id and only identifies the circuit (single-circuit sync).
     */
    public Circuit findCircuit(UUID circuitId) {
        if (circuitId == null) {
            return null;
        }
        for (World w : worlds) {
            Circuit c = w.findCircuit(circuitId);
            if (c != null) {
                return c;
            }
        }
        return null;
    }
}
