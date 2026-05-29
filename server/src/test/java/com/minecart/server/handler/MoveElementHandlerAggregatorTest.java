package com.minecart.server.handler;

import com.minecart.elements.component.BJTransistor;
import com.minecart.elements.edge.Resistor;
import com.minecart.logic.CircuitNode;
import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;
import com.minecart.protocol.payload.client.MoveElementPayload;
import com.minecart.registry.AllComponents;
import com.minecart.registry.AllElementInfos;
import com.minecart.variant.info.LockInfo;
import com.minecart.variant.info.LockMode;
import com.minecart.variant.info.PositionInfo;
import com.minecart.variant.info.RigidityInfo;
import com.minecart.variant.info.RotationInfo;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Integration tests for {@link MoveElementHandler}'s aggregator routing. The handler dispatches on
 * the payload's {@code gestureId}:
 * <ul>
 *   <li>{@code gestureId != null} → push into the drag aggregator. The body moves only when
 *       {@link ServerLevel#tick} flushes the aggregator.</li>
 *   <li>{@code gestureId == null} → one-shot synchronous translation via the cascade engine.</li>
 * </ul>
 *
 * <p>These tests exercise both modes against the live world + level + handler stack so the wiring
 * is verified end-to-end, not just the inner physics adapter.
 */
class MoveElementHandlerAggregatorTest {

    private static final double EPS = 0.1;

    private static BJTransistor placeBJT(ServerWorld w, double cx, double cy) {
        BJTransistor bjt = w.createComponent(AllComponents.BJ_TRANSISTOR);
        bjt.setInfo(AllElementInfos.POSITION, new PositionInfo(cx, cy));
        bjt.setInfo(AllElementInfos.ROTATION, new RotationInfo(0.0));
        place(bjt.getPort(0), cx - 1.0, cy);
        place(bjt.getPort(1), cx + 1.0, cy + 0.5);
        place(bjt.getPort(2), cx + 1.0, cy - 0.5);
        place(bjt.getCenter(), cx, cy);
        return bjt;
    }

    private static void place(CircuitNode n, double x, double y) {
        PositionInfo p = n.getInfo(AllElementInfos.POSITION);
        if (p == null) {
            n.setInfo(AllElementInfos.POSITION, new PositionInfo(x, y));
        } else {
            p.set(x, y);
        }
    }

    private static void markRigid(com.minecart.logic.CircuitEdge edge) {
        RigidityInfo r = edge.getInfo(AllElementInfos.RIGIDITY);
        if (r == null) {
            r = new RigidityInfo();
            edge.setInfo(AllElementInfos.RIGIDITY, r);
        }
        r.setRigid(true);
    }

    @Test
    void streamingPayload_doesNotMoveBeforeTickFlush() {
        // A streaming Move (gestureId != null) is buffered, not applied immediately. The element's
        // position only updates once ServerLevel.tick() flushes the aggregator.
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor bjt = placeBJT(w, 0.0, 0.0);
        MoveElementHandler handler = new MoveElementHandler(level);

        UUID gesture = UUID.randomUUID();
        handler.handle(new MoveElementPayload(w.getId(), bjt.getId(), gesture, 5.0, 0.0));

        // The handler's level.submit ran the enqueue when we drain by calling tick(); BEFORE the
        // tick the position is still at the original spot.
        // (The submit-driven enqueue runs INSIDE tick. So an uninspected state is the pre-tick one.)
        assertEquals(0.0, bjt.getInfo(AllElementInfos.POSITION).getX(), 1e-9);

        level.tick();

        assertEquals(5.0, bjt.getInfo(AllElementInfos.POSITION).getX(), EPS);
        assertEquals(0.0, bjt.getInfo(AllElementInfos.POSITION).getY(), EPS);
    }

    @Test
    void oneShotPayload_appliesSynchronouslyThroughCascadeEngine() {
        // A Move WITHOUT a gestureId (panel save, etc.) is applied immediately through the cascade
        // engine inside the action-queue phase of the tick — no aggregator buffer needed.
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor bjt = placeBJT(w, 0.0, 0.0);
        MoveElementHandler handler = new MoveElementHandler(level);

        handler.handle(new MoveElementPayload(w.getId(), bjt.getId(), 5.0, 0.0));
        level.tick();

        // Hard-pin path: lands exactly at target.
        assertEquals(5.0, bjt.getInfo(AllElementInfos.POSITION).getX(), 1e-9);
        assertEquals(0.0, bjt.getInfo(AllElementInfos.POSITION).getY(), 1e-9);
    }

    @Test
    void streamingPayload_lockedElement_isRefusedAndAuthoritativePoseSticks() {
        // Locked element receives a streaming drag → aggregator refuses (strict-lock preflight),
        // notifyElementChanged still fires for the unchanged authoritative pose. Body unchanged.
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor bjt = placeBJT(w, 0.0, 0.0);
        LockInfo lock = new LockInfo();
        lock.setMode(LockMode.LOCKED);
        lock.setPivot(0.0, 0.0);
        bjt.setInfo(AllElementInfos.LOCK, lock);

        MoveElementHandler handler = new MoveElementHandler(level);
        handler.handle(new MoveElementPayload(w.getId(), bjt.getId(), UUID.randomUUID(),
                5.0, 5.0));
        level.tick();

        assertEquals(0.0, bjt.getInfo(AllElementInfos.POSITION).getX(), 1e-9);
        assertEquals(0.0, bjt.getInfo(AllElementInfos.POSITION).getY(), 1e-9);
    }

    @Test
    void streamingPayload_sameElementTwoGestures_isImmobilisedForTick() {
        // Two distinct gestures target the same element in one tick → contention → element is
        // immobilised. Neither gesture wins; the body stays at its current pose.
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor bjt = placeBJT(w, 0.0, 0.0);
        MoveElementHandler handler = new MoveElementHandler(level);

        handler.handle(new MoveElementPayload(w.getId(), bjt.getId(), UUID.randomUUID(),
                5.0, 0.0));
        handler.handle(new MoveElementPayload(w.getId(), bjt.getId(), UUID.randomUUID(),
                -5.0, 0.0));
        level.tick();

        // Contention policy: body unchanged at (0, 0).
        assertEquals(0.0, bjt.getInfo(AllElementInfos.POSITION).getX(), 1e-9);
        assertEquals(0.0, bjt.getInfo(AllElementInfos.POSITION).getY(), 1e-9);
    }

    @Test
    void streamingPayload_coalescesMultipleSamplesOfSameGesture() {
        // One gesture, three samples in the same tick. Only the latest sample's target should
        // matter; intermediate samples are no-ops at flush time.
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor bjt = placeBJT(w, 0.0, 0.0);
        MoveElementHandler handler = new MoveElementHandler(level);

        UUID gesture = UUID.randomUUID();
        handler.handle(new MoveElementPayload(w.getId(), bjt.getId(), gesture, 1.0, 0.0));
        handler.handle(new MoveElementPayload(w.getId(), bjt.getId(), gesture, 3.0, 0.0));
        handler.handle(new MoveElementPayload(w.getId(), bjt.getId(), gesture, 7.0, 4.0));
        level.tick();

        // Final pose = the latest sample's target.
        assertEquals(7.0, bjt.getInfo(AllElementInfos.POSITION).getX(), EPS);
        assertEquals(4.0, bjt.getInfo(AllElementInfos.POSITION).getY(), EPS);
    }

    @Test
    void streamingPayload_acrossRigidEdge_propagatesToCoupledElement() {
        // A spring drag on one BJT linked by a rigid wire to another BJT pulls the other along.
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor left = placeBJT(w, 0.0, 0.0);
        BJTransistor right = placeBJT(w, 4.0, 0.0);
        Resistor wire = w.connect(AllComponents.RESISTOR, left.getPort(2), right.getPort(2));
        assertNotNull(wire);
        markRigid(wire);

        MoveElementHandler handler = new MoveElementHandler(level);
        handler.handle(new MoveElementPayload(w.getId(), left.getId(), UUID.randomUUID(),
                3.0, 0.0));
        level.tick();

        // Left landed near target. Right was pushed along by the rigid wire.
        assertEquals(3.0, left.getInfo(AllElementInfos.POSITION).getX(), EPS);
        // Right moved past its original 4.0 to maintain the wire's rest length.
        org.junit.jupiter.api.Assertions.assertTrue(
                right.getInfo(AllElementInfos.POSITION).getX() > 4.0,
                "right BJT should have been pushed by the rigid wire; was at "
                        + right.getInfo(AllElementInfos.POSITION).getX());
    }
}
