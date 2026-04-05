package com.minecart.client.payload.server.element;

import com.minecart.event.events.ElementEvent;
import com.minecart.foundation.Circuit;
import com.minecart.logic.CircuitEdge;
import com.minecart.logic.CircuitElement;
import com.minecart.logic.CircuitNode;
import com.minecart.foundation.Level;
import com.minecart.client.payload.IncrementPayloadListener;
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
 * records an ordered {@link CircuitElementChange} list per circuit, and flushes via {@link #sync(Consumer)}. Payloads are
 * {@link com.minecart.client.payload.Payload.Destination#CLIENT}-bound (server replication).
 * <p>
 * Implements {@link IncrementPayloadListener} for incremental element deltas; batched flush uses {@link #sync(Consumer)}
 * rather than {@link #nextPayload()} (which always returns {@code null}).
 */
public final class CircuitElementListener implements IncrementPayloadListener<CircuitElementPayload> {

    private final Level level;
    private final Consumer<CircuitElementPayload> defaultSink;
    private final Consumer<ElementEvent.ElementInsertEvent> insertHandler = this::onInsert;
    private final Consumer<ElementEvent.ElementRemoveEvent> removeHandler = this::onRemove;

    private final Map<CircuitKey, Delta> pending = new LinkedHashMap<>();
    private boolean attached;

    public CircuitElementListener(Level level) {
        this(level, null);
    }

    /**
     * @param defaultSink if non-null, {@link #sync()} delegates here; otherwise use {@link #sync(Consumer)}.
     */
    public CircuitElementListener(Level level, Consumer<CircuitElementPayload> defaultSink) {
        this.level = Objects.requireNonNull(level, "level");
        this.defaultSink = defaultSink;
    }

    /** Registers on {@link Level#getEventBus()} for insert/remove events. Idempotent. */
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
     * Not used for batched element deltas; call {@link #sync(Consumer)} or {@link #sync()} to emit
     * {@link CircuitElementPayload} instances.
     */
    @Override
    public CircuitElementPayload nextPayload() {
        return null;
    }

    /**
     * Ordered list of keys for which there is at least one pending change since the last {@link #sync}.
     */
    public synchronized List<CircuitKey> getPendingCircuitKeys() {
        return List.copyOf(pending.keySet());
    }

    /**
     * Sends pending deltas using the constructor-provided sink, then clears. Requires a non-null default sink.
     */
    public synchronized void sync() {
        if (defaultSink == null) {
            throw new IllegalStateException("No default sink; use sync(Consumer<CircuitElementPayload>) or construct with a sink");
        }
        sync(defaultSink);
    }

    /**
     * Builds one {@link CircuitElementPayload} per altered circuit, delivers each to {@code sink}, then clears state.
     * If nothing is pending, {@code sink} is not invoked.
     */
    public synchronized void sync(Consumer<CircuitElementPayload> sink) {
        Objects.requireNonNull(sink, "sink");
        if (pending.isEmpty()) {
            return;
        }
        for (Map.Entry<CircuitKey, Delta> e : pending.entrySet()) {
            CircuitElementPayload payload = e.getValue().toPayload(e.getKey());
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

    /**
     * World and circuit ids used to route a {@link CircuitElementPayload}.
     */
    public record CircuitKey(UUID worldId, UUID circuitId) {
        public CircuitKey {
            Objects.requireNonNull(circuitId, "circuitId");
        }
    }

    private static final class Delta {
        private final List<CircuitElementChange> ops = new ArrayList<>();

        void onInsert(CircuitElement el) {
            CompoundTag data = CircuitElement.serialize(el);
            ops.add(CircuitElementChange.insert(CircuitElementChange.registryTypeIdOf(el), data));
        }

        void onRemove(CircuitElement el) {
            ops.add(CircuitElementChange.remove(el.getId()));
        }

        CircuitElementPayload toPayload(CircuitKey key) {
            return new CircuitElementPayload(key.worldId(), key.circuitId(), new ArrayList<>(ops));
        }
    }
}
