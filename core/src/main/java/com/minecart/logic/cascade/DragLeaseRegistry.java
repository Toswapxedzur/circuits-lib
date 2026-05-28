package com.minecart.logic.cascade;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side bookkeeping of element-level <em>drag leases</em>. While a client is actively
 * dragging element {@code E}, the level holds a lease for {@code E} keyed by the drag's
 * {@code gestureId}. Other clients trying to drag (or combine, rotate, etc.) any of the
 * leased elements have their payload refused, because mutating an element another client is
 * also moving would race, and the cascade engine's atomic plan-then-apply can't reason about
 * mid-flight changes from elsewhere.
 *
 * <h2>Gesture ids, not session ids</h2>
 * The registry is keyed by gesture id rather than session id intentionally: gesture ids are unique
 * per drag-per-client (the client generates a fresh UUID on each {@code touchDown}), so two
 * sequential drags from the same client trivially get separate leases without needing a session
 * concept. Multi-client conflict prevention falls out naturally because each client's gesture id is
 * also unique across clients.
 *
 * <p>This <em>does</em> mean a crashed-mid-drag client leaks a lease forever — there's currently no
 * heartbeat. Mitigation: on channel close the dispatcher releases every lease registered through
 * that channel (TODO: wire that up when the multi-client server lands). Single-process integrated
 * server doesn't need it since there's only one channel.
 *
 * <h2>Threading</h2>
 * Every public method is {@code synchronized}. The server-tick path is single-threaded (everything
 * runs through {@link com.minecart.logic.ServerLevel#submit(Runnable)}) so the lock is mostly
 * uncontended, but the channel-close cleanup path runs on Netty's event loop and contends with the
 * tick — the synchronisation lets that contention be safe.
 */
public final class DragLeaseRegistry {

    /** Element-id → gesture-id of the active lease. {@code null}-valued entries are absent. */
    private final Map<UUID, UUID> leasedBy = new HashMap<>();
    /** Reverse index: gesture-id → set of element-ids held by that gesture. */
    private final Map<UUID, Set<UUID>> heldByGesture = new HashMap<>();

    /**
     * Attempts to take leases on every element in {@code elementIds} under {@code gestureId}.
     * Returns {@code true} on success; {@code false} (and acquires nothing) when any requested
     * element is already leased by a <em>different</em> gesture. Re-acquiring elements already
     * held by the same gesture is allowed and idempotent — this matters when a drag expands its
     * lease set mid-flight (e.g. an edge drag that picks up a second movable on hover).
     */
    public synchronized boolean tryAcquire(UUID gestureId, Collection<UUID> elementIds) {
        if (gestureId == null || elementIds == null || elementIds.isEmpty()) {
            return false;
        }
        // First pass: validate no conflicts. Don't mutate until we know we can commit, so a
        // failed acquire leaves the registry exactly as it was — important when a caller probes
        // multiple gesture candidates and only commits the winner.
        for (UUID id : elementIds) {
            if (id == null) continue;
            UUID owner = leasedBy.get(id);
            if (owner != null && !owner.equals(gestureId)) {
                return false;
            }
        }
        // Second pass: commit.
        Set<UUID> held = heldByGesture.computeIfAbsent(gestureId, k -> new HashSet<>());
        for (UUID id : elementIds) {
            if (id == null) continue;
            leasedBy.put(id, gestureId);
            held.add(id);
        }
        return true;
    }

    /**
     * Releases every lease held by {@code gestureId}. No-op when the gesture has no active leases —
     * an idempotent end-of-drag end-handler is useful because clients sometimes send DragEnd
     * defensively on disconnect.
     */
    public synchronized void release(UUID gestureId) {
        if (gestureId == null) return;
        Set<UUID> held = heldByGesture.remove(gestureId);
        if (held == null) return;
        for (UUID id : held) {
            UUID owner = leasedBy.get(id);
            // Defensive: only remove the entry if it's still ours. A concurrent re-acquire under
            // a different gesture (shouldn't happen with the synchronisation, but belt-and-braces)
            // would leave a stale-looking owner; refuse to clobber it.
            if (owner != null && owner.equals(gestureId)) {
                leasedBy.remove(id);
            }
        }
    }

    /**
     * Returns {@code true} when {@code elementId} is either unleased or leased by {@code gestureId}
     * itself. Used by mutation handlers to gate "is the caller allowed to touch this element?"
     *
     * <p>A {@code null} gestureId is treated as "no gesture claimed" and only passes the check when
     * the element is unleased. This is intentionally strict: a mutation that didn't go through a
     * drag begin can still proceed, but only on elements nobody else is dragging.
     */
    public synchronized boolean isOwnedBy(UUID elementId, UUID gestureId) {
        UUID owner = leasedBy.get(elementId);
        if (owner == null) return true;
        return owner.equals(gestureId);
    }

    /**
     * Convenience batch form of {@link #isOwnedBy}. Returns {@code true} iff every element in
     * {@code elementIds} would pass the single-element check.
     */
    public synchronized boolean canOperateOn(UUID gestureId, Collection<UUID> elementIds) {
        if (elementIds == null) return true;
        for (UUID id : elementIds) {
            if (id == null) continue;
            if (!isOwnedBy(id, gestureId)) return false;
        }
        return true;
    }

    /**
     * Snapshot of elements currently leased by {@code gestureId}. Returned as an immutable copy so
     * callers can iterate without holding the registry lock. Empty when the gesture has no leases.
     */
    public synchronized Set<UUID> elementsHeldBy(UUID gestureId) {
        Set<UUID> held = heldByGesture.get(gestureId);
        if (held == null || held.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(held));
    }

    /** Total number of currently-leased elements. Mostly useful for tests + debugging. */
    public synchronized int leaseCount() {
        return leasedBy.size();
    }
}
