package com.minecart.logic.cascade;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for {@link DragLeaseRegistry}: acquire / release semantics, conflict detection, and
 * idempotency. The registry is the Phase 2c bookkeeping primitive — every other Phase 2c piece
 * (handlers, cascade lease check, client wiring) is correctness-derived from this contract.
 */
class DragLeaseRegistryTest {

    @Test
    void acquire_freeElements_succeeds() {
        DragLeaseRegistry r = new DragLeaseRegistry();
        UUID g = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertTrue(r.tryAcquire(g, List.of(a, b)));
        assertEquals(2, r.leaseCount());
        assertTrue(r.isOwnedBy(a, g));
        assertTrue(r.isOwnedBy(b, g));
    }

    @Test
    void acquire_conflictingElement_failsAtomically() {
        DragLeaseRegistry r = new DragLeaseRegistry();
        UUID g1 = UUID.randomUUID();
        UUID g2 = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();

        assertTrue(r.tryAcquire(g1, List.of(a)));
        // g2 wants [b, a, c] — fails on a. Atomic: b and c must NOT be acquired by g2.
        assertFalse(r.tryAcquire(g2, List.of(b, a, c)));
        assertTrue(r.isOwnedBy(b, g2),
                "unleased elements stay unleased even when batch acquire fails (so isOwnedBy(b, g2) is true via the null-owner branch)");
        assertEquals(1, r.leaseCount(), "only g1's original lease should remain");

        // g2 now wants only [b, c] — should succeed since a was the only conflict.
        assertTrue(r.tryAcquire(g2, List.of(b, c)));
        assertEquals(3, r.leaseCount());
    }

    @Test
    void acquire_alreadyOwnedBySameGesture_isIdempotent() {
        // Drag controllers sometimes expand a lease mid-flight (edge drag picking up a second
        // movable). The registry must let the same gesture re-acquire its own elements without
        // returning a spurious failure.
        DragLeaseRegistry r = new DragLeaseRegistry();
        UUID g = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertTrue(r.tryAcquire(g, List.of(a)));
        assertTrue(r.tryAcquire(g, List.of(a, b)));
        assertEquals(2, r.leaseCount());
        assertTrue(r.isOwnedBy(a, g));
        assertTrue(r.isOwnedBy(b, g));
    }

    @Test
    void release_clearsLeases_andLeavesOthersUntouched() {
        DragLeaseRegistry r = new DragLeaseRegistry();
        UUID g1 = UUID.randomUUID();
        UUID g2 = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        r.tryAcquire(g1, List.of(a));
        r.tryAcquire(g2, List.of(b));
        r.release(g1);
        assertEquals(1, r.leaseCount());
        assertFalse(r.isOwnedBy(b, g1), "b is now owned by g2, gesture g1 shouldn't be reported as owner");
        assertTrue(r.isOwnedBy(b, g2));
        // a is unleased — anyone can claim.
        UUID g3 = UUID.randomUUID();
        assertTrue(r.tryAcquire(g3, List.of(a)));
    }

    @Test
    void release_unknownGesture_isNoOp() {
        DragLeaseRegistry r = new DragLeaseRegistry();
        r.release(UUID.randomUUID());
        assertEquals(0, r.leaseCount());
    }

    @Test
    void canOperateOn_unleasedElements_pass() {
        DragLeaseRegistry r = new DragLeaseRegistry();
        UUID g = UUID.randomUUID();
        // Even a null gesture id can operate on elements nobody has leased.
        assertTrue(r.canOperateOn(null, List.of(UUID.randomUUID(), UUID.randomUUID())));
        assertTrue(r.canOperateOn(g, List.of(UUID.randomUUID())));
    }

    @Test
    void canOperateOn_foreignLease_fails() {
        DragLeaseRegistry r = new DragLeaseRegistry();
        UUID g1 = UUID.randomUUID();
        UUID g2 = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        r.tryAcquire(g1, List.of(a));
        assertFalse(r.canOperateOn(g2, List.of(a)));
        assertFalse(r.canOperateOn(null, List.of(a)),
                "a null gestureId can't override an active lease — non-gesture mutations only operate on free elements");
        assertTrue(r.canOperateOn(g1, List.of(a)));
    }

    @Test
    void elementsHeldBy_snapshotsCurrentLeases() {
        DragLeaseRegistry r = new DragLeaseRegistry();
        UUID g = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        r.tryAcquire(g, List.of(a, b));
        Set<UUID> snap = r.elementsHeldBy(g);
        assertTrue(snap.contains(a));
        assertTrue(snap.contains(b));
        // Mutating the registry after the snapshot shouldn't affect the returned set's content
        // (it's an unmodifiable copy).
        r.release(g);
        assertEquals(2, snap.size());
    }
}
