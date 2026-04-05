package com.minecart.foundation;

import com.minecart.event.EventBus;
import com.minecart.event.events.Event;
import com.minecart.event.events.RegisterElementChangeListenerEvent;
import com.minecart.logic.CircuitElement;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Holds level-wide state: tick configuration, event bus, and the {@link World}s attached to this level.
 * {@link com.minecart.logic.ServerLevel} adds the global tick loop and deferred actions.
 */
public class Level {

    protected double tickRate = 0.05;

    private final EventBus eventBus = new EventBus();

    private final Set<World> worlds = new LinkedHashSet<>();

    private boolean initialized;
    private Consumer<CircuitElement> elementChangeNotifier = el -> {};

    public Level(){
        initialized = false;
    }

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

    /**
     * Runs once before the first tick: posts {@link RegisterElementChangeListenerEvent} so handlers can
     * {@link RegisterElementChangeListenerEvent#register register} {@link Consumer}s, then installs the combined notifier
     * for {@link #notifyElementChanged}. Idempotent after the first run.
     */
    public void init() {
        if (initialized) {
            throw new UnsupportedOperationException("Registration already frozen");
        }
        RegisterElementChangeListenerEvent event = new RegisterElementChangeListenerEvent(this);
        post(event);
        elementChangeNotifier = event.getNotifier();
    }

    /**
     * Must be called before the first tick ran
     */
    public void setInitialized() {
        this.initialized = true;
    }

    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Notifies all combined element-change listeners (typically from {@link CircuitElement} when replicated state changes).
     */
    public void notifyElementChanged(CircuitElement element) {
        Objects.requireNonNull(element, "element");
        elementChangeNotifier.accept(element);
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
