package com.minecart.server.listener;

import com.minecart.event.events.CircuitLifecycleEvent;
import com.minecart.foundation.Level;
import com.minecart.protocol.payload.server.CircuitLifecyclePayload;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Subscribes to {@link CircuitLifecycleEvent.CircuitInsertEvent} and
 * {@link CircuitLifecycleEvent.CircuitRemoveEvent} on a {@link Level} and forwards them as
 * {@link CircuitLifecyclePayload}s through {@code sink}. Replication only — server topology is
 * authoritative; the client mirror needs these messages to know a circuit exists before any
 * {@link com.minecart.protocol.payload.server.CircuitElementPayload} delta references it.
 *
 * <p>Insert and remove payloads are queued separately so the {@code IntegratedServer} can flush them
 * in the correct order around an element-sync pass:
 * <ol>
 *     <li>{@link #syncInserts(Consumer)} — announce new circuits FIRST so element INSERT deltas have a target.</li>
 *     <li>{@code elementListener.sync()} — apply element deltas against existing circuits.</li>
 *     <li>{@link #syncRemoves(Consumer)} — drop circuits LAST so element REMOVE deltas can still resolve.</li>
 * </ol>
 *
 * <p>Without that ordering, a single tick that both adds and removes circuits can leave the client trying to
 * apply deltas against a circuit it has either never been told about or already been told to drop.
 */
public class CircuitLifecycleListener {

    private final Level level;
    private final Consumer<CircuitLifecyclePayload> defaultSink;
    private final Consumer<CircuitLifecycleEvent.CircuitInsertEvent> insertHandler = this::onInsert;
    private final Consumer<CircuitLifecycleEvent.CircuitRemoveEvent> removeHandler = this::onRemove;

    private final List<Entry> pendingInserts = new ArrayList<>();
    private final List<Entry> pendingRemoves = new ArrayList<>();
    private boolean attached;

    public CircuitLifecycleListener(Level level) {
        this(level, null);
    }

    /**
     * @param defaultSink if non-null, {@link #syncInserts()} / {@link #syncRemoves()} delegate here.
     */
    public CircuitLifecycleListener(Level level, Consumer<CircuitLifecyclePayload> defaultSink) {
        this.level = Objects.requireNonNull(level, "level");
        this.defaultSink = defaultSink;
    }

    /** Idempotent: registers listeners for circuit insert/remove events. */
    public void attach() {
        if (attached) {
            return;
        }
        level.register(CircuitLifecycleEvent.CircuitInsertEvent.class, insertHandler);
        level.register(CircuitLifecycleEvent.CircuitRemoveEvent.class, removeHandler);
        attached = true;
    }

    /** Idempotent: unregisters listeners. */
    public void detach() {
        if (!attached) {
            return;
        }
        level.unregister(CircuitLifecycleEvent.CircuitInsertEvent.class, insertHandler);
        level.unregister(CircuitLifecycleEvent.CircuitRemoveEvent.class, removeHandler);
        attached = false;
    }

    public boolean isAttached() {
        return attached;
    }

    /** Flush pending INSERTs to the default sink, then clear. */
    public synchronized void syncInserts() {
        if (defaultSink == null) {
            throw new IllegalStateException("No default sink; use syncInserts(Consumer) or construct with a sink");
        }
        syncInserts(defaultSink);
    }

    /** Flush pending REMOVEs to the default sink, then clear. */
    public synchronized void syncRemoves() {
        if (defaultSink == null) {
            throw new IllegalStateException("No default sink; use syncRemoves(Consumer) or construct with a sink");
        }
        syncRemoves(defaultSink);
    }

    /** Flush pending INSERTs through {@code sink} in arrival order, then clear. */
    public synchronized void syncInserts(Consumer<CircuitLifecyclePayload> sink) {
        Objects.requireNonNull(sink, "sink");
        if (pendingInserts.isEmpty()) {
            return;
        }
        for (Entry e : pendingInserts) {
            sink.accept(CircuitLifecyclePayload.insert(e.worldId(), e.circuitId()));
        }
        pendingInserts.clear();
    }

    /** Flush pending REMOVEs through {@code sink} in arrival order, then clear. */
    public synchronized void syncRemoves(Consumer<CircuitLifecyclePayload> sink) {
        Objects.requireNonNull(sink, "sink");
        if (pendingRemoves.isEmpty()) {
            return;
        }
        for (Entry e : pendingRemoves) {
            sink.accept(CircuitLifecyclePayload.remove(e.worldId(), e.circuitId()));
        }
        pendingRemoves.clear();
    }

    private void onInsert(CircuitLifecycleEvent.CircuitInsertEvent event) {
        if (!attached || event.getWorld() == null || event.getCircuit() == null) {
            return;
        }
        synchronized (this) {
            // Same circuit may have been pending REMOVE earlier in the same tick (rare: an insert
            // immediately rolled back then re-created). Drop the prior REMOVE so the client doesn't
            // see "remove a circuit it never had" or "insert duplicate" — net effect: it exists.
            pendingRemoves.removeIf(e -> e.matches(event.getWorld().getId(), event.getCircuit().getId()));
            pendingInserts.add(new Entry(event.getWorld().getId(), event.getCircuit().getId()));
        }
    }

    private void onRemove(CircuitLifecycleEvent.CircuitRemoveEvent event) {
        if (!attached || event.getWorld() == null || event.getCircuit() == null) {
            return;
        }
        synchronized (this) {
            // If we're about to announce a circuit but it got removed in the same tick (insert then
            // remove), cancel the announcement entirely.
            boolean cancelled = pendingInserts.removeIf(
                    e -> e.matches(event.getWorld().getId(), event.getCircuit().getId()));
            if (cancelled) {
                return;
            }
            pendingRemoves.add(new Entry(event.getWorld().getId(), event.getCircuit().getId()));
        }
    }

    private record Entry(UUID worldId, UUID circuitId) {
        boolean matches(UUID w, UUID c) {
            return Objects.equals(worldId, w) && Objects.equals(circuitId, c);
        }
    }
}
