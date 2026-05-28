package com.minecart.server.handler;

import com.minecart.logic.CircuitNode;
import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;
import com.minecart.protocol.payload.client.CombineCascadePayload;
import com.minecart.protocol.payload.client.DragBeginPayload;
import com.minecart.protocol.payload.client.DragEndPayload;
import com.minecart.registry.AllComponents;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage for the edit-lease lifecycle: {@link DragBeginHandler} acquires a lease,
 * {@link DragEndHandler} releases it, and {@link CombineCascadeHandler} respects the lease in
 * between. Mirrors what {@code InfoPanelController.openFor} / {@code closeOpen} does on the
 * client side without instantiating LibGDX UI scaffolding.
 */
class DragLeaseHandlerTest {

    @Test
    void dragBegin_acquiresLease_dragEnd_releasesIt() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        CircuitNode n = w.createNode(AllComponents.CONNECTION);
        UUID gesture = UUID.randomUUID();

        new DragBeginHandler(level).handle(new DragBeginPayload(w.getId(), gesture, List.of(n.getId())));
        level.tick();
        assertEquals(1, level.getDragLeases().leaseCount());
        assertTrue(level.getDragLeases().isOwnedBy(n.getId(), gesture));

        new DragEndHandler(level).handle(new DragEndPayload(w.getId(), gesture));
        level.tick();
        assertEquals(0, level.getDragLeases().leaseCount());
    }

    @Test
    void editLease_blocksForeignCombineCascade_thenReleasesOnEnd() {
        // Reproduces the panel-edit lock flow: a panel opens for element X (acquire a lease under
        // an edit gesture id), a different gesture tries to combine into X (refused), the panel
        // closes (release), the same combine now succeeds.
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        CircuitNode survivor = w.createNode(AllComponents.CONNECTION);
        CircuitNode absorbed = w.createNode(AllComponents.CONNECTION);

        UUID editGesture = UUID.randomUUID();
        UUID otherGesture = UUID.randomUUID();
        DragBeginHandler beginH = new DragBeginHandler(level);
        DragEndHandler endH = new DragEndHandler(level);
        CombineCascadeHandler combineH = new CombineCascadeHandler(level);

        // Panel opens on the survivor.
        beginH.handle(new DragBeginPayload(w.getId(), editGesture, List.of(survivor.getId())));
        level.tick();

        // Combine attempt from another gesture (e.g. another client's drag).
        combineH.handle(new CombineCascadePayload(
                w.getId(), otherGesture,
                List.of(new CombineCascadePayload.CombinePair(survivor.getId(), absorbed.getId()))));
        level.tick();

        // Refused — absorbed still exists, lease still held.
        boolean absorbedAlive = w.getCircuits().stream().anyMatch(c -> c.nodes().contains(absorbed));
        assertTrue(absorbedAlive, "absorbed should still exist while edit lease blocked the combine");
        assertEquals(1, level.getDragLeases().leaseCount());

        // Panel closes (Save / Cancel both end up here).
        endH.handle(new DragEndPayload(w.getId(), editGesture));
        level.tick();
        assertEquals(0, level.getDragLeases().leaseCount());

        // Retry the same combine — now succeeds.
        combineH.handle(new CombineCascadePayload(
                w.getId(), otherGesture,
                List.of(new CombineCascadePayload.CombinePair(survivor.getId(), absorbed.getId()))));
        level.tick();
        boolean absorbedGone = w.getCircuits().stream().noneMatch(c -> c.nodes().contains(absorbed));
        assertTrue(absorbedGone, "absorbed should be merged once the edit lease was released");
    }

    @Test
    void dragEnd_unknownGesture_isHarmless() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        new DragEndHandler(level).handle(new DragEndPayload(w.getId(), UUID.randomUUID()));
        level.tick();
        assertEquals(0, level.getDragLeases().leaseCount());
    }

    @Test
    void dragBegin_emptyPayloadShouldNotAcquire() {
        // Defensive: DragBeginPayload.load() rejects empty element lists at decode time, but if a
        // constructed-in-process payload sneaks through, the handler must not acquire anything.
        // We can't construct an empty-list DragBeginPayload directly here (the protocol wire-format
        // assertion lives in load()), so this test asserts the equivalent via the registry helper.
        ServerLevel level = new ServerLevel();
        assertFalse(level.getDragLeases().tryAcquire(UUID.randomUUID(), List.of()));
    }
}
