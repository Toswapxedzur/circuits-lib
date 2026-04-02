package com.minecart.client.payload.server.topology;

import com.minecart.event.events.ElementEvent;
import com.minecart.logic.Circuit;
import com.minecart.logic.CircuitComponent;
import com.minecart.logic.CircuitEdge;
import com.minecart.logic.CircuitElement;
import com.minecart.logic.CircuitNode;
import com.minecart.logic.Level;
import com.minecart.serialization.tag.CompoundTag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Subscribes to {@link ElementEvent.ElementInsertEvent} and {@link ElementEvent.ElementRemoveEvent} on a {@link Level},
 * records an ordered {@link CircuitTopologyChange} list per circuit, and flushes via {@link #sync(Consumer)}.
 */
public final class CircuitTopologyListener {

    private final Level level;
    private final Consumer<CircuitTopologyPayload> defaultSink;
    private final Consumer<ElementEvent.ElementInsertEvent> insertHandler = this::onInsert;
    private final Consumer<ElementEvent.ElementRemoveEvent> removeHandler = this::onRemove;

    private final Map<CircuitKey, Delta> pending = new LinkedHashMap<>();
    private boolean attached;

    public CircuitTopologyListener(Level level) {
        this(level, null);
    }

    /**
     * @param defaultSink if non-null, {@link #sync()} delegates here; otherwise use {@link #sync(Consumer)}.
     */
    public CircuitTopologyListener(Level level, Consumer<CircuitTopologyPayload> defaultSink) {
        this.level = Objects.requireNonNull(level, "level");
        this.defaultSink = defaultSink;
    }

    /** Registers on {@link Level#getEventBus()} for insert/remove topology events. Idempotent. */
    public void attach() {
        if (attached) {
            return;
        }
        level.register(ElementEvent.ElementInsertEvent.class, insertHandler);
        level.register(ElementEvent.ElementRemoveEvent.class, removeHandler);
        attached = true;
    }

    /** Unregisters listeners. */
    public void detach() {
        if (!attached) {
            return;
        }
        level.unregister(ElementEvent.ElementInsertEvent.class, insertHandler);
        level.unregister(ElementEvent.ElementRemoveEvent.class, removeHandler);
        attached = false;
    }

    public boolean isAttached() {
        return attached;
    }

    /**
     * Ordered list of keys for which there is at least one pending insert or remove since the last {@link #sync}.
     */
    public synchronized List<CircuitKey> getPendingCircuitKeys() {
        return List.copyOf(pending.keySet());
    }

    /**
     * Sends pending deltas using the constructor-provided sink, then clears. Requires a non-null default sink.
     */
    public synchronized void sync() {
        if (defaultSink == null) {
            throw new IllegalStateException("No default sink; use sync(Consumer<CircuitTopologyPayload>) or construct with a sink");
        }
        sync(defaultSink);
    }

    /**
     * Builds one {@link CircuitTopologyPayload} per altered circuit, delivers each to {@code sink}, then clears state.
     * If nothing is pending, {@code sink} is not invoked.
     */
    public synchronized void sync(Consumer<CircuitTopologyPayload> sink) {
        Objects.requireNonNull(sink, "sink");
        if (pending.isEmpty()) {
            return;
        }
        for (Map.Entry<CircuitKey, Delta> e : pending.entrySet()) {
            CircuitTopologyPayload payload = e.getValue().toPayload(e.getKey());
            if (!payload.getChanges().isEmpty()) {
                sink.accept(payload);
            }
        }
        pending.clear();
    }

    private void onInsert(ElementEvent.ElementInsertEvent event) {
        if (!attached) {
            return;
        }
        CircuitElement el = event.getElement();
        CircuitKey key = keyFor(el);
        if (key == null) {
            return;
        }
        synchronized (this) {
            pending.computeIfAbsent(key, k -> new Delta()).onInsert(el);
        }
    }

    private void onRemove(ElementEvent.ElementRemoveEvent event) {
        if (!attached) {
            return;
        }
        CircuitElement el = event.getElement();
        CircuitKey key = keyFor(el);
        if (key == null) {
            return;
        }
        synchronized (this) {
            pending.computeIfAbsent(key, k -> new Delta()).onRemove(el);
        }
    }

    private static CircuitKey keyFor(CircuitElement el) {
        if (el.getWorld() == null) {
            return null;
        }
        Circuit c = resolveCircuit(el);
        if (c == null) {
            return null;
        }
        return new CircuitKey(el.getWorld().getId(), c.getId());
    }

    private static Circuit resolveCircuit(CircuitElement el) {
        Circuit c = el.getCircuit();
        if (c != null) {
            return c;
        }
        if (el instanceof CircuitEdge e) {
            CircuitNode s = e.getStart();
            if (s != null) {
                return s.getCircuit();
            }
        }
        return null;
    }

    private static CompoundTag serialize(CircuitElement el) {
        CompoundTag tag = new CompoundTag();
        switch (el) {
            case CircuitNode n -> n.save(tag);
            case CircuitEdge e -> e.save(tag);
            case CircuitComponent c -> c.save(tag);
            default -> throw new IllegalStateException("Unknown circuit element type: " + el.getClass());
        }
        return tag;
    }

    /**
     * World and circuit ids used to route a {@link CircuitTopologyPayload}.
     */
    public record CircuitKey(UUID worldId, UUID circuitId) {
        public CircuitKey {
            Objects.requireNonNull(circuitId, "circuitId");
        }
    }

    private static final class Delta {
        private final List<CircuitTopologyChange> ops = new ArrayList<>();

        void onInsert(CircuitElement el) {
            CircuitTopologyChange.ElementKind ek =
                    switch (el) {
                        case CircuitNode n -> CircuitTopologyChange.ElementKind.NODE;
                        case CircuitEdge e -> CircuitTopologyChange.ElementKind.EDGE;
                        case CircuitComponent c -> CircuitTopologyChange.ElementKind.COMPONENT;
                        default -> throw new IllegalStateException("Unknown element: " + el.getClass());
                    };
            ops.add(CircuitTopologyChange.insert(ek, serialize(el)));
        }

        void onRemove(CircuitElement el) {
            ops.add(CircuitTopologyChange.remove(el.getId()));
        }

        CircuitTopologyPayload toPayload(CircuitKey key) {
            return new CircuitTopologyPayload(key.worldId(), key.circuitId(), new ArrayList<>(ops));
        }
    }
}
