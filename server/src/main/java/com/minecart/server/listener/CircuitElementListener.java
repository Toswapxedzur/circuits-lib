package com.minecart.server.listener;

import com.minecart.event.events.ElementCircuitChangedEvent;
import com.minecart.event.events.ElementEvent;
import com.minecart.event.events.RegisterElementChangeListenerEvent;
import com.minecart.foundation.Circuit;
import com.minecart.foundation.Level;
import com.minecart.logic.CircuitEdge;
import com.minecart.logic.CircuitElement;
import com.minecart.logic.CircuitNode;
import com.minecart.protocol.payload.IncrementPayloadListener;
import com.minecart.protocol.payload.server.CircuitElementChange;
import com.minecart.protocol.payload.server.CircuitElementPayload;
import com.minecart.serialization.tag.CompoundTag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Subscribes to {@link ElementEvent.ElementInsertEvent}, {@link ElementEvent.ElementRemoveEvent},
 * {@link ElementCircuitChangedEvent}, and (via {@link RegisterElementChangeListenerEvent}) per-tick element
 * sync notifications on a {@link Level}. Accumulates one {@link ElementState} entry per touched element and,
 * at {@link #sync(Consumer)} time, collapses each entry into at most one {@link CircuitElementChange} keyed
 * by the element's <em>final</em> circuit.
 *
 * <h2>Why per-element instead of per-circuit</h2>
 * A single tick can shuffle an element across several transient circuits (component generation creates a
 * fresh circuit per node, then merges them; edge insertion in the editor merges two real circuits; edge
 * removal splits one). If we keyed ops by the circuit the element was in <em>at event time</em>, the
 * resulting deltas would reference intermediate circuits the client never learned about and the matching
 * lifecycle insert/remove pair would have been coalesced away by {@link CircuitLifecycleListener}. Resolving
 * the final state at sync time eliminates that whole class of "phantom circuit" bugs.
 *
 * <h3>Ordering within a circuit's payload</h3>
 * Rebind CHANGEs (source != null) sort first so the client moves any migrated endpoints into the
 * destination circuit before any edge INSERT in the same payload tries to look them up. INSERTs follow,
 * then plain CHANGEs, then REMOVEs.
 *
 * <p>Call {@link #attach()} before {@link Level#init()} (typically before the first
 * {@link com.minecart.logic.ServerLevel#tick()}) so per-tick {@code onChange} subscription is included.
 *
 * <p>Implements {@link IncrementPayloadListener} for API parity, but the batched flush uses
 * {@link #sync(Consumer)} rather than {@link #nextPayload()} (which always returns {@code null}).
 */
public class CircuitElementListener implements IncrementPayloadListener<CircuitElementPayload> {

    private final Level level;
    private final Consumer<CircuitElementPayload> defaultSink;
    private final Consumer<ElementEvent.ElementInsertEvent> insertHandler = this::onInsert;
    private final Consumer<ElementEvent.ElementRemoveEvent> removeHandler = this::onRemove;
    private final Consumer<ElementCircuitChangedEvent> rebindHandler = this::onCircuitChanged;
    private final Consumer<RegisterElementChangeListenerEvent> registerElementChangeHandler = this::onRegisterElementChange;

    /** Keyed by element id so duplicate events for the same element coalesce into one entry. */
    private final Map<UUID, ElementState> pending = new LinkedHashMap<>();
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

    /** Registers on {@link Level#getEventBus()} for insert/remove, circuit-rebind, and {@link RegisterElementChangeListenerEvent}. Idempotent. */
    public void attach() {
        if (attached) {
            return;
        }
        level.register(ElementEvent.ElementInsertEvent.class, insertHandler);
        level.register(ElementEvent.ElementRemoveEvent.class, removeHandler);
        level.register(ElementCircuitChangedEvent.class, rebindHandler);
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
        level.unregister(ElementCircuitChangedEvent.class, rebindHandler);
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
     * Ordered list of destination circuit keys that {@link #sync(Consumer)} would emit a payload for.
     * Computed by collapsing the per-element entries to their current circuit (or {@code lastKnownCircuitId}
     * for removed elements). Returned in payload-emit order. Mainly useful for tests.
     */
    public synchronized List<CircuitKey> getPendingCircuitKeys() {
        List<CircuitKey> keys = new ArrayList<>();
        LinkedHashMap<CircuitKey, Boolean> seen = new LinkedHashMap<>();
        for (ElementState s : pending.values()) {
            CircuitKey key = destinationKeyFor(s);
            if (key == null) continue;
            if (seen.putIfAbsent(key, Boolean.TRUE) == null) {
                keys.add(key);
            }
        }
        return List.copyOf(keys);
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
     * Builds one {@link CircuitElementPayload} per destination circuit by walking every accumulated
     * {@link ElementState}, deciding the net op (INSERT / REMOVE / CHANGE possibly with rebind source) at
     * sync time based on the element's <em>current</em> circuit, and delivers each payload to {@code sink}.
     * Clears state. If nothing collapses to a real op, {@code sink} is not invoked.
     */
    public synchronized void sync(Consumer<CircuitElementPayload> sink) {
        Objects.requireNonNull(sink, "sink");
        if (pending.isEmpty()) {
            return;
        }
        LinkedHashMap<CircuitKey, List<CircuitElementChange>> byCircuit = new LinkedHashMap<>();
        for (ElementState s : pending.values()) {
            CircuitElementChange op = resolveOp(s);
            if (op == null) continue;
            CircuitKey key = destinationKeyFor(s);
            if (key == null) continue;
            byCircuit.computeIfAbsent(key, k -> new ArrayList<>()).add(op);
        }
        pending.clear();
        for (Map.Entry<CircuitKey, List<CircuitElementChange>> e : byCircuit.entrySet()) {
            List<CircuitElementChange> ops = e.getValue();
            // Stable sort within each circuit's payload: rebind CHANGEs first (so a moved endpoint reaches
            // the destination before an edge INSERT in the same payload looks it up), then INSERTs, then
            // plain CHANGEs, then REMOVEs. Arrival order is preserved within each priority bucket so a node
            // INSERT keeps coming before an edge INSERT that references it.
            ops.sort(Comparator.comparingInt(CircuitElementListener::priorityOf));
            sink.accept(new CircuitElementPayload(e.getKey().worldId(), e.getKey().circuitId(), ops));
        }
    }

    private static int priorityOf(CircuitElementChange c) {
        if (c.kind() == CircuitElementChange.Kind.CHANGE && c.sourceCircuitId() != null) {
            return 0;
        }
        return switch (c.kind()) {
            case INSERT -> 1;
            case CHANGE -> 2;
            case REMOVE -> 3;
        };
    }

    /**
     * Resolves an {@link ElementState} into the net wire op for this tick, or {@code null} if the entry
     * collapses to a no-op (same-tick insert+remove, or a CHANGE on an element whose circuit and sync data
     * didn't actually change).
     */
    private static CircuitElementChange resolveOp(ElementState s) {
        if (s.inserted && s.removed) {
            // Born and died in the same tick; client never needs to know.
            return null;
        }
        if (s.removed) {
            return CircuitElementChange.remove(s.element.getId());
        }
        if (s.inserted) {
            // New element: emit a single INSERT keyed by the element's *final* circuit. No rebind needed
            // because the client has never seen this element before — its only "previous" circuits this
            // tick were transient ones the client was never told about.
            CompoundTag data = CircuitElement.serialize(s.element);
            return CircuitElementChange.insert(CircuitElementChange.registryTypeIdOf(s.element), data);
        }
        // Pre-existing element: check for circuit rebind and/or sync-data change.
        Circuit current = currentCircuit(s.element);
        if (current == null) {
            return null;
        }
        UUID currId = current.getId();
        boolean rebind = s.firstSourceCircuitId != null && !s.firstSourceCircuitId.equals(currId);
        CompoundTag data = s.changed
                ? CircuitElementChange.changeFromSync(s.element).data()
                : null;
        if (rebind) {
            return CircuitElementChange.changeWithRebind(
                    CircuitElementChange.registryTypeIdOf(s.element),
                    s.element.getId(),
                    data,
                    s.firstSourceCircuitId);
        }
        if (data != null) {
            return CircuitElementChange.change(
                    CircuitElementChange.registryTypeIdOf(s.element),
                    s.element.getId(),
                    data);
        }
        return null;
    }

    /**
     * Picks the destination {@link CircuitKey} for an entry: current circuit for inserts and live elements,
     * {@code lastKnownCircuitId} for removals (since {@code element.getCircuit()} may be null after destroy).
     */
    private CircuitKey destinationKeyFor(ElementState s) {
        UUID worldId = s.element.getWorld() != null ? s.element.getWorld().getId() : null;
        if (worldId == null) {
            return null;
        }
        UUID circuitId;
        if (s.removed) {
            circuitId = s.lastKnownCircuitId;
        } else {
            Circuit c = currentCircuit(s.element);
            circuitId = c != null ? c.getId() : null;
        }
        if (circuitId == null) {
            return null;
        }
        return new CircuitKey(worldId, circuitId);
    }

    private void onInsert(ElementEvent.ElementInsertEvent event) {
        if (!attached) {
            return;
        }
        CircuitElement el = event.getElement();
        if (el == null) {
            return;
        }
        synchronized (this) {
            pending.computeIfAbsent(el.getId(), k -> new ElementState(el)).inserted = true;
        }
    }

    private void onRemove(ElementEvent.ElementRemoveEvent event) {
        if (!attached) {
            return;
        }
        CircuitElement el = event.getElement();
        if (el == null) {
            return;
        }
        synchronized (this) {
            ElementState s = pending.get(el.getId());
            if (s != null && s.inserted) {
                // Net no-op: born and died this tick.
                pending.remove(el.getId());
                return;
            }
            s = pending.computeIfAbsent(el.getId(), k -> new ElementState(el));
            s.removed = true;
            // el.getCircuit() may still be non-null at the moment the remove event fires; capture it now
            // since destroy paths may clear the reference afterwards.
            Circuit c = currentCircuit(el);
            if (c != null) {
                s.lastKnownCircuitId = c.getId();
            }
        }
    }

    private void onCircuitChanged(ElementCircuitChangedEvent event) {
        if (!attached || event.getWorld() == null || event.getNewCircuit() == null
                || event.getOldCircuit() == null || event.getElement() == null) {
            return;
        }
        UUID srcId = event.getOldCircuit().getId();
        UUID destId = event.getNewCircuit().getId();
        if (srcId == null || destId == null || srcId.equals(destId)) {
            return;
        }
        synchronized (this) {
            ElementState s = pending.computeIfAbsent(
                    event.getElement().getId(), k -> new ElementState(event.getElement()));
            // Only the FIRST source matters: if an element hopped C1 -> C2 -> C3 this tick, the net visible
            // move is C1 -> (whatever its current circuit is at sync time). C2 is invisible to the client.
            if (s.firstSourceCircuitId == null) {
                s.firstSourceCircuitId = srcId;
            }
        }
    }

    private void onRegisterElementChange(RegisterElementChangeListenerEvent event) {
        if (!attached || event.getLevel() != level) {
            return;
        }
        event.register(this::onChange);
    }

    /**
     * Called when {@link Level#notifyElementChanged} runs for an element (after {@link Level#init()}).
     * Marks the element's pending entry as {@code changed}; the sync data itself is serialized at
     * {@link #sync(Consumer)} time so any later mutations within the same tick are captured.
     */
    protected void onChange(CircuitElement el) {
        if (el == null) {
            return;
        }
        synchronized (this) {
            pending.computeIfAbsent(el.getId(), k -> new ElementState(el)).changed = true;
        }
    }

    private static Circuit currentCircuit(CircuitElement el) {
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

    /**
     * Per-element accumulator. Lives only for one tick (cleared by {@link #sync(Consumer)}); the
     * {@code element} reference is safe to hold across the tick because all mutation runs on the tick thread.
     */
    private static final class ElementState {
        final CircuitElement element;
        boolean inserted;
        boolean removed;
        boolean changed;
        /** Old circuit id captured by the FIRST {@link ElementCircuitChangedEvent} for this element. */
        UUID firstSourceCircuitId;
        /** Last known circuit id at the moment of remove, used to route the REMOVE op. */
        UUID lastKnownCircuitId;

        ElementState(CircuitElement element) {
            this.element = element;
        }
    }
}
