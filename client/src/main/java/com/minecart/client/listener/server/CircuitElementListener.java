package com.minecart.client.listener.server;

import com.minecart.client.payload.server.CircuitElementChange;
import com.minecart.client.payload.server.CircuitElementPayload;
import com.minecart.event.events.ElementEvent;
import com.minecart.event.events.RegisterElementChangeListenerEvent;
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
 * and registers {@link #onChange} during {@link Level#init()} via {@link RegisterElementChangeListenerEvent}.
 * Records an ordered {@link CircuitElementChange} list per circuit, and flushes via {@link #sync(Consumer)}. Payloads are
 * {@link com.minecart.client.payload.Payload.Destination#CLIENT}-bound (server replication).
 * <p>
 * Call {@link #attach()} before {@link Level#init()} runs (e.g. before the first {@link com.minecart.logic.ServerLevel#tick()}
 * or {@link com.minecart.client.logic.ClientLevel#tick()}) so element-change registration is included.
 * <p>
 * Implements {@link IncrementPayloadListener} for incremental element deltas; batched flush uses {@link #sync(Consumer)}
 * rather than {@link #nextPayload()} (which always returns {@code null}).
 */
public class CircuitElementListener implements IncrementPayloadListener<CircuitElementPayload> {

    private final Level level;
    private final Consumer<CircuitElementPayload> defaultSink;
    private final Consumer<ElementEvent.ElementInsertEvent> insertHandler = this::onInsert;
    private final Consumer<ElementEvent.ElementRemoveEvent> removeHandler = this::onRemove;
    private final Consumer<RegisterElementChangeListenerEvent> registerElementChangeHandler = this::onRegisterElementChange;

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

    /** Registers on {@link Level#getEventBus()} for insert/remove and {@link RegisterElementChangeListenerEvent}. Idempotent. */
    public void attach() {
        if (attached) {
            return;
        }
        level.register(ElementEvent.ElementInsertEvent.class, insertHandler);
        level.register(ElementEvent.ElementRemoveEvent.class, removeHandler);
        level.register(RegisterElementChangeListenerEvent.class, registerElementChangeHandler);
        attached = true;
    }

    /** Unregisters listeners. */
    public void detach() {
        if (!attached) {
            return;
        }
        level.unregister(ElementEvent.ElementInsertEvent.class, insertHandler);
        level.unregister(ElementEvent.ElementRemoveEvent.class, removeHandler);
        level.unregister(RegisterElementChangeListenerEvent.class, registerElementChangeHandler);
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

    private void onRegisterElementChange(RegisterElementChangeListenerEvent event) {
        if (!attached || event.getLevel() != level) {
            return;
        }
        event.register(this::onChange);
    }

    /**
     * Called when {@link Level#notifyElementChanged} runs for an element (after {@link Level#init()}). Default
     * implementation records a {@link CircuitElementChange.Kind#CHANGE} step for the next {@link #sync}.
     */
    protected void onChange(CircuitElement el) {
        CircuitKey key = keyFor(el);
        if (key == null) {
            return;
        }
        synchronized (this) {
            pending.computeIfAbsent(key, k -> new Delta()).onChange(el);
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

        void onChange(CircuitElement el) {
            ops.add(CircuitElementChange.changeFromSync(el));
        }

        CircuitElementPayload toPayload(CircuitKey key) {
            return new CircuitElementPayload(key.worldId(), key.circuitId(), new ArrayList<>(ops));
        }
    }
}
