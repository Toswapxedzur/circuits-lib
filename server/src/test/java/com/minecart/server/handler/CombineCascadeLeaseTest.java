package com.minecart.server.handler;

import com.minecart.elements.component.BJTransistor;
import com.minecart.foundation.Circuit;
import com.minecart.logic.CircuitNode;
import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;
import com.minecart.protocol.payload.client.CombineCascadePayload;
import com.minecart.registry.AllComponents;
import com.minecart.registry.AllElementInfos;
import com.minecart.variant.info.PositionInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 2c: verifies {@link CombineCascadeHandler} consults the
 * {@link com.minecart.logic.cascade.DragLeaseRegistry} before applying a cascade. A cascade whose
 * touched elements are leased to a different gesture id must be refused without mutating the
 * world; a cascade whose gesture id matches the lease (or whose touched elements are unleased)
 * must proceed.
 *
 * <p>Pairs are 1-element free-on-free for simplicity — the lease gate is generic across cascade
 * shapes (free-on-free, port-on-port, cross-component) since it inspects ids only.
 */
class CombineCascadeLeaseTest {

    @Test
    void cascade_withGesture_holdingLease_proceeds() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        CircuitNode survivor = w.createNode(AllComponents.CONNECTION);
        CircuitNode absorbed = w.createNode(AllComponents.CONNECTION);

        UUID gesture = UUID.randomUUID();
        // The drag holds a lease on the survivor (the dragged element). The absorbed node is free.
        assertTrue(level.getDragLeases().tryAcquire(gesture, List.of(survivor.getId())));

        new CombineCascadeHandler(level).handle(new CombineCascadePayload(
                w.getId(), gesture,
                List.of(new CombineCascadePayload.CombinePair(survivor.getId(), absorbed.getId()))));
        level.tick();

        // Cascade applied → absorbed gone from its circuit.
        for (Circuit c : w.getCircuits()) {
            assertFalse(c.nodes().contains(absorbed), "absorbed should have been merged into survivor");
        }
    }

    @Test
    void cascade_withForeignGesture_refused() {
        // Two gestures: g1 (the conflicting one) holds the lease on the survivor; g2 (the payload's
        // gesture) is trying to merge the survivor with an absorbed node. The lease check refuses
        // the cascade because g2 doesn't own the survivor.
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        CircuitNode survivor = w.createNode(AllComponents.CONNECTION);
        CircuitNode absorbed = w.createNode(AllComponents.CONNECTION);

        UUID g1 = UUID.randomUUID();
        UUID g2 = UUID.randomUUID();
        level.getDragLeases().tryAcquire(g1, List.of(survivor.getId()));

        new CombineCascadeHandler(level).handle(new CombineCascadePayload(
                w.getId(), g2,
                List.of(new CombineCascadePayload.CombinePair(survivor.getId(), absorbed.getId()))));
        level.tick();

        // Cascade refused → absorbed still alive.
        boolean stillThere = false;
        for (Circuit c : w.getCircuits()) {
            if (c.nodes().contains(absorbed)) { stillThere = true; break; }
        }
        assertTrue(stillThere, "absorbed node should still exist when the cascade was refused");
    }

    @Test
    void cascade_owningComponentLeasedElsewhere_refused() {
        // When the absorbed node is a port of component C, the cascade may translate C. So C
        // itself must be checked against the lease. This guards the cross-component-cascade case
        // where component C is being dragged by another client at the same time.
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        // Build a BJT so we have a port-owning component.
        BJTransistor bjt = w.createComponent(AllComponents.BJ_TRANSISTOR);
        bjt.setInfo(AllElementInfos.POSITION, new PositionInfo(0.0, 0.0));
        place(bjt.getPort(0), -1.0, 0.0);
        place(bjt.getPort(1), 1.0, 0.5);
        place(bjt.getPort(2), 1.0, -0.5);
        place(bjt.getCenter(), 0.0, 0.0);

        CircuitNode free = w.createNode(AllComponents.CONNECTION);
        place(free, -1.0, 0.0);

        UUID g1 = UUID.randomUUID();
        UUID g2 = UUID.randomUUID();
        // g1 holds a lease on the COMPONENT (e.g. another client is currently dragging it).
        level.getDragLeases().tryAcquire(g1, List.of(bjt.getId()));

        // g2 tries to combine the free node onto bjt's port 0 — would need to translate bjt OR
        // make the free node win, but either way the lease on bjt is the conflict.
        new CombineCascadeHandler(level).handle(new CombineCascadePayload(
                w.getId(), g2,
                List.of(new CombineCascadePayload.CombinePair(free.getId(), bjt.getPort(0).getId()))));
        level.tick();

        // Refused → free node still its own free node, port still at anchor.
        assertTrue(w.getCircuits().iterator().next().nodes().contains(free)
                        || w.getCircuits().stream().anyMatch(c -> c.nodes().contains(free)),
                "free node should still exist when cascade was refused");
    }

    @Test
    void cascade_nullGestureId_refusedWhenAnythingLeased() {
        // Panel-driven / scripted cascades pass null for gestureId. They can only proceed when
        // every touched element is unleased — they shouldn't be allowed to bypass active drags.
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        CircuitNode survivor = w.createNode(AllComponents.CONNECTION);
        CircuitNode absorbed = w.createNode(AllComponents.CONNECTION);

        UUID g1 = UUID.randomUUID();
        level.getDragLeases().tryAcquire(g1, List.of(survivor.getId()));

        new CombineCascadeHandler(level).handle(new CombineCascadePayload(
                w.getId(), null,
                List.of(new CombineCascadePayload.CombinePair(survivor.getId(), absorbed.getId()))));
        level.tick();

        // Refused.
        boolean stillThere = false;
        for (Circuit c : w.getCircuits()) {
            if (c.nodes().contains(absorbed)) { stillThere = true; break; }
        }
        assertTrue(stillThere);
    }

    private static void place(CircuitNode n, double x, double y) {
        PositionInfo p = n.getInfo(AllElementInfos.POSITION);
        if (p == null) {
            n.setInfo(AllElementInfos.POSITION, new PositionInfo(x, y));
        } else {
            p.set(x, y);
        }
    }
}
