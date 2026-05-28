package com.minecart.server.handler;

import com.minecart.elements.component.BJTransistor;
import com.minecart.logic.CircuitNode;
import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;
import com.minecart.protocol.payload.client.RotateElementPayload;
import com.minecart.registry.AllComponents;
import com.minecart.registry.AllElementInfos;
import com.minecart.variant.info.LockInfo;
import com.minecart.variant.info.LockMode;
import com.minecart.variant.info.PositionInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct unit coverage for {@link RotateElementHandler}. The handler is the Phase 3c bridge between
 * the gesture payload ({@link RotateElementPayload}) and {@link com.minecart.logic.cascade.CombineCascadeEngine}.
 *
 * <p>End-to-end Netty round-trip coverage lives in {@code CombinePayloadRoundTripTest}; this file
 * just exercises the handler's two-step apply: (1) update strict {@link LockInfo} so the pivot
 * coincides with the requested pivot, (2) call the engine's
 * {@link com.minecart.logic.cascade.CombineCascadeEngine#tryRotateComponent rotation} helper.
 */
class RotateElementHandlerTest {

    private static final double EPS = 1e-6;

    private static void place(CircuitNode n, double x, double y) {
        PositionInfo p = n.getInfo(AllElementInfos.POSITION);
        if (p == null) {
            n.setInfo(AllElementInfos.POSITION, new PositionInfo(x, y));
        } else {
            p.set(x, y);
        }
    }

    private static BJTransistor placeBJT(ServerWorld w, double cx, double cy) {
        BJTransistor bjt = w.createComponent(AllComponents.BJ_TRANSISTOR);
        bjt.setInfo(AllElementInfos.POSITION, new PositionInfo(cx, cy));
        // Anchor offsets per BJTransistor: collector at (-1, 0), base at (1, 0.5), emitter at (1, -0.5),
        // centre at (0, 0). Apply them around (cx, cy).
        place(bjt.getPort(0), cx - 1.0, cy);
        place(bjt.getPort(1), cx + 1.0, cy + 0.5);
        place(bjt.getPort(2), cx + 1.0, cy - 0.5);
        place(bjt.getCenter(), cx, cy);
        return bjt;
    }

    @Test
    void scrollRotate_freeComponent_appliesRotationAndPromotesLockToRotationFree() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor bjt = placeBJT(w, 0.0, 0.0);

        RotateElementHandler handler = new RotateElementHandler(level);
        // Quarter turn around a point off the component — exercises the "pivot != centre"
        // codepath so we can also check that the body actually translates.
        handler.handle(new RotateElementPayload(
                w.getId(), bjt.getId(), 2.0, 0.0, Math.PI / 2.0));
        level.tick();

        // Lock promoted from FREE → ROTATION_FREE; pivot recorded.
        LockInfo lock = bjt.getInfo(AllElementInfos.LOCK);
        assertNotNull(lock, "rotation gesture should create a LockInfo when none exists");
        assertEquals(LockMode.ROTATION_FREE, lock.getMode());
        assertEquals(2.0, lock.getPivotX(), EPS);
        assertEquals(0.0, lock.getPivotY(), EPS);
        assertTrue(lock.isPivotSet());

        // Body rotated 90° around (2, 0): old centre (0, 0) → new (2, -2).
        assertEquals(2.0, bjt.getInfo(AllElementInfos.POSITION).getX(), EPS);
        assertEquals(-2.0, bjt.getInfo(AllElementInfos.POSITION).getY(), EPS);
    }

    @Test
    void secondGesture_atDifferentPivot_isAcceptedByUpdatingPivot() {
        // Whole reason the handler writes the pivot to strictLock BEFORE calling the engine: the
        // engine refuses a ROTATION_FREE component if the strict pivot doesn't coincide with the
        // requested pivot. Two consecutive gestures at different pivots therefore both need to
        // succeed — that's the day-to-day scroll-and-move-cursor UX.
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor bjt = placeBJT(w, 0.0, 0.0);

        RotateElementHandler handler = new RotateElementHandler(level);
        handler.handle(new RotateElementPayload(w.getId(), bjt.getId(), 0.0, 0.0, Math.PI / 4.0));
        level.tick();
        handler.handle(new RotateElementPayload(w.getId(), bjt.getId(), 5.0, 5.0, Math.PI / 4.0));
        level.tick();

        LockInfo lock = bjt.getInfo(AllElementInfos.LOCK);
        assertNotNull(lock);
        assertEquals(LockMode.ROTATION_FREE, lock.getMode());
        // Pivot tracks the LATEST gesture's pivot, not the first one's.
        assertEquals(5.0, lock.getPivotX(), EPS);
        assertEquals(5.0, lock.getPivotY(), EPS);
    }

    @Test
    void lockedElement_isNotRotatedByGesture() {
        // When the user manually LOCKED the element via the panel, gestures must NOT silently
        // override it. The handler returns early so the rotation never lands.
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor bjt = placeBJT(w, 0.0, 0.0);
        LockInfo lock = new LockInfo();
        lock.setMode(LockMode.LOCKED);
        bjt.setInfo(AllElementInfos.LOCK, lock);

        double centreXBefore = bjt.getInfo(AllElementInfos.POSITION).getX();
        double centreYBefore = bjt.getInfo(AllElementInfos.POSITION).getY();

        RotateElementHandler handler = new RotateElementHandler(level);
        handler.handle(new RotateElementPayload(
                w.getId(), bjt.getId(), 3.0, 3.0, Math.PI / 3.0));
        level.tick();

        assertEquals(LockMode.LOCKED, bjt.getInfo(AllElementInfos.LOCK).getMode());
        assertEquals(centreXBefore, bjt.getInfo(AllElementInfos.POSITION).getX(), EPS);
        assertEquals(centreYBefore, bjt.getInfo(AllElementInfos.POSITION).getY(), EPS);
    }

    @Test
    void unmutableLock_isNotRotatedByGesture() {
        // Permanent strict lock (mutableByPlayer=false) is the strongest form: panel UI can't flip
        // it and gestures shouldn't either. Mirrors PositionInfo.isFixed()'s contract.
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor bjt = placeBJT(w, 0.0, 0.0);
        LockInfo lock = new LockInfo(LockMode.POSITION_FREE, 0.0, 0.0, false);
        bjt.setInfo(AllElementInfos.LOCK, lock);

        double centreXBefore = bjt.getInfo(AllElementInfos.POSITION).getX();
        double centreYBefore = bjt.getInfo(AllElementInfos.POSITION).getY();

        RotateElementHandler handler = new RotateElementHandler(level);
        handler.handle(new RotateElementPayload(
                w.getId(), bjt.getId(), 1.0, 1.0, Math.PI / 6.0));
        level.tick();

        // Lock + body untouched.
        assertEquals(LockMode.POSITION_FREE, bjt.getInfo(AllElementInfos.LOCK).getMode());
        assertEquals(centreXBefore, bjt.getInfo(AllElementInfos.POSITION).getX(), EPS);
        assertEquals(centreYBefore, bjt.getInfo(AllElementInfos.POSITION).getY(), EPS);
    }

    @Test
    void nonComponentTarget_isIgnored() {
        // Free node is currently out of rotation scope. The handler should swallow the payload
        // silently rather than crash or attach a LockInfo to a node that doesn't have a rotation
        // model.
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        CircuitNode free = w.createNode(AllComponents.CONNECTION);
        place(free, 4.0, 4.0);

        RotateElementHandler handler = new RotateElementHandler(level);
        handler.handle(new RotateElementPayload(
                w.getId(), free.getId(), 0.0, 0.0, Math.PI / 2.0));
        level.tick();

        // No lock created, position unchanged.
        assertNull(free.getInfo(AllElementInfos.LOCK));
        assertEquals(4.0, free.getInfo(AllElementInfos.POSITION).getX(), EPS);
        assertEquals(4.0, free.getInfo(AllElementInfos.POSITION).getY(), EPS);
    }

    @Test
    void positionFreeLock_isUpgradedToRotationFreeOnGesture_no_wait_keepsModeButUpdatesPivot() {
        // Per the handler doc: POSITION_FREE permits rotation only because the AND with the engine's
        // softLock allows it... actually POSITION_FREE explicitly forbids rotation. The handler
        // promotes ONLY FREE → ROTATION_FREE; POSITION_FREE stays POSITION_FREE, which means the
        // engine refuses the rotation (because POSITION_FREE doesn't allow rotation). So a
        // POSITION_FREE element is effectively rotation-immune via gestures — the same as LOCKED.
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor bjt = placeBJT(w, 0.0, 0.0);
        LockInfo lock = new LockInfo(LockMode.POSITION_FREE, 0.0, 0.0, true);
        bjt.setInfo(AllElementInfos.LOCK, lock);

        double centreXBefore = bjt.getInfo(AllElementInfos.POSITION).getX();
        double centreYBefore = bjt.getInfo(AllElementInfos.POSITION).getY();

        RotateElementHandler handler = new RotateElementHandler(level);
        handler.handle(new RotateElementPayload(
                w.getId(), bjt.getId(), 2.0, 0.0, Math.PI / 2.0));
        level.tick();

        // Mode preserved, pivot updated (handler always writes the pivot), body NOT rotated
        // because POSITION_FREE forbids rotation in the engine.
        assertEquals(LockMode.POSITION_FREE, bjt.getInfo(AllElementInfos.LOCK).getMode());
        assertEquals(2.0, bjt.getInfo(AllElementInfos.LOCK).getPivotX(), EPS);
        assertEquals(0.0, bjt.getInfo(AllElementInfos.LOCK).getPivotY(), EPS);
        assertEquals(centreXBefore, bjt.getInfo(AllElementInfos.POSITION).getX(), EPS);
        assertEquals(centreYBefore, bjt.getInfo(AllElementInfos.POSITION).getY(), EPS);
    }
}
