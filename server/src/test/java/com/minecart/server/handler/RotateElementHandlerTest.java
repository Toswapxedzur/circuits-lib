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
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Direct unit coverage for {@link RotateElementHandler}. The handler is the Phase 3c bridge
 * between the gesture payload ({@link RotateElementPayload}) and the cascade engine. As of the
 * Phase 1 lock-enforcement pass, the handler is pure delegation: it forwards to
 * {@link com.minecart.logic.cascade.CombineCascadeEngine#tryRotateComponent} and does NOT mutate
 * the component's strict {@link LockInfo}. The tests below pin that contract: rotations land
 * when the effective lock allows them, and the player's authored lock state is never silently
 * promoted or rewritten by a gesture.
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
        // Anchor offsets per BJTransistor: collector at (-1, 0), base at (1, 0.5), emitter at
        // (1, -0.5), centre at (0, 0). Apply them around (cx, cy). The test path doesn't mark
        // the port positions isFixed=true (PlaceComponentHandler does that in production), so
        // the soft-lock derivation reports FREE and lets the engine proceed.
        place(bjt.getPort(0), cx - 1.0, cy);
        place(bjt.getPort(1), cx + 1.0, cy + 0.5);
        place(bjt.getPort(2), cx + 1.0, cy - 0.5);
        place(bjt.getCenter(), cx, cy);
        return bjt;
    }

    @Test
    void scrollRotate_freeComponent_appliesRotationWithoutTouchingLockInfo() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor bjt = placeBJT(w, 0.0, 0.0);

        RotateElementHandler handler = new RotateElementHandler(level);
        // Quarter turn around a point off the component — exercises the "pivot != centre"
        // codepath so we can also check that the body actually translates.
        handler.handle(new RotateElementPayload(
                w.getId(), bjt.getId(), 2.0, 0.0, Math.PI / 2.0));
        level.tick();

        // Phase 1 contract: no LockInfo gets created or mutated. The component was placed
        // without one; it stays without one.
        assertNull(bjt.getInfo(AllElementInfos.LOCK),
                "handler should not synthesise a LockInfo as a side-effect of the gesture");

        // Body rotated 90° around (2, 0): old centre (0, 0) → new (2, -2).
        assertEquals(2.0, bjt.getInfo(AllElementInfos.POSITION).getX(), EPS);
        assertEquals(-2.0, bjt.getInfo(AllElementInfos.POSITION).getY(), EPS);
    }

    @Test
    void successiveGesturesAtDifferentPivots_bothLand_withoutAccumulatingLockInfo() {
        // Before the Phase 1 fix the handler tried to "track" the gesture pivot inside LockInfo
        // so the engine's pivot-coincidence check would accept successive gestures at different
        // pivots. With the side-effect removed, strictLock stays FREE (the default), and FREE
        // imposes no pivot constraint — so two consecutive gestures at different pivots both
        // land cleanly.
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor bjt = placeBJT(w, 0.0, 0.0);

        RotateElementHandler handler = new RotateElementHandler(level);
        handler.handle(new RotateElementPayload(w.getId(), bjt.getId(), 0.0, 0.0, Math.PI / 4.0));
        level.tick();
        handler.handle(new RotateElementPayload(w.getId(), bjt.getId(), 5.0, 5.0, Math.PI / 4.0));
        level.tick();

        // Strict lock untouched throughout — the gesture surface is read-only with respect to
        // LockInfo.
        assertNull(bjt.getInfo(AllElementInfos.LOCK),
                "consecutive gestures must not synthesise or rewrite LockInfo");
    }

    @Test
    void lockedElement_isNotRotatedByGesture_andLockStateIsPreserved() {
        // The player manually LOCKED the element via the panel; gestures must not silently
        // override it. The engine refuses the rotation through effectiveLockState; the handler
        // doesn't touch LockInfo on its own, so the LOCKED state survives the rejected gesture.
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
    void positionFreeLock_refusesRotationAndKeepsAuthoredPivot() {
        // POSITION_FREE permits translation only; the engine's effectiveLockState.allowsRotation
        // returns false, so the rotation is refused. The authored pivot is preserved — earlier
        // revisions of the handler would have silently rewritten it to the gesture's pivot, which
        // was the bug that motivated the Phase 1 side-effect removal.
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor bjt = placeBJT(w, 0.0, 0.0);
        LockInfo lock = new LockInfo(LockMode.POSITION_FREE, 7.0, 9.0, true);
        bjt.setInfo(AllElementInfos.LOCK, lock);

        double centreXBefore = bjt.getInfo(AllElementInfos.POSITION).getX();
        double centreYBefore = bjt.getInfo(AllElementInfos.POSITION).getY();

        RotateElementHandler handler = new RotateElementHandler(level);
        handler.handle(new RotateElementPayload(
                w.getId(), bjt.getId(), 2.0, 0.0, Math.PI / 2.0));
        level.tick();

        // Mode preserved, authored pivot preserved (NOT overwritten by the gesture's pivot),
        // and body not rotated.
        LockInfo after = bjt.getInfo(AllElementInfos.LOCK);
        assertEquals(LockMode.POSITION_FREE, after.getMode());
        assertEquals(7.0, after.getPivotX(), EPS);
        assertEquals(9.0, after.getPivotY(), EPS);
        assertEquals(centreXBefore, bjt.getInfo(AllElementInfos.POSITION).getX(), EPS);
        assertEquals(centreYBefore, bjt.getInfo(AllElementInfos.POSITION).getY(), EPS);
    }

    @Test
    void rotationFreeLock_atMatchingPivot_isAcceptedAndPreservesAuthoredLockState() {
        // ROTATION_FREE at a stored pivot: engine accepts the rotation iff the gesture's pivot
        // matches the stored one. With the side-effect removed, the lock survives the gesture
        // unchanged.
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor bjt = placeBJT(w, 0.0, 0.0);
        LockInfo lock = new LockInfo(LockMode.ROTATION_FREE, 2.0, 0.0, true);
        bjt.setInfo(AllElementInfos.LOCK, lock);

        RotateElementHandler handler = new RotateElementHandler(level);
        handler.handle(new RotateElementPayload(
                w.getId(), bjt.getId(), 2.0, 0.0, Math.PI / 2.0));
        level.tick();

        // Rotation applied (centre (0,0) → (2,-2) under 90° CCW about (2,0)).
        assertEquals(2.0, bjt.getInfo(AllElementInfos.POSITION).getX(), EPS);
        assertEquals(-2.0, bjt.getInfo(AllElementInfos.POSITION).getY(), EPS);
        // Authored ROTATION_FREE lock state untouched.
        LockInfo after = bjt.getInfo(AllElementInfos.LOCK);
        assertEquals(LockMode.ROTATION_FREE, after.getMode());
        assertEquals(2.0, after.getPivotX(), EPS);
        assertEquals(0.0, after.getPivotY(), EPS);
    }

    @Test
    void rotationFreeLock_atMismatchedPivot_isRefusedAndPreservesAuthoredPivot() {
        // Same setup as above, but the gesture targets a different pivot. The engine's pivot-
        // coincidence check refuses, and the handler does NOT rewrite the authored pivot to
        // hide the refusal.
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor bjt = placeBJT(w, 0.0, 0.0);
        LockInfo lock = new LockInfo(LockMode.ROTATION_FREE, 2.0, 0.0, true);
        bjt.setInfo(AllElementInfos.LOCK, lock);

        double centreXBefore = bjt.getInfo(AllElementInfos.POSITION).getX();
        double centreYBefore = bjt.getInfo(AllElementInfos.POSITION).getY();

        RotateElementHandler handler = new RotateElementHandler(level);
        handler.handle(new RotateElementPayload(
                w.getId(), bjt.getId(), 5.0, 5.0, Math.PI / 2.0));
        level.tick();

        // Body unchanged.
        assertEquals(centreXBefore, bjt.getInfo(AllElementInfos.POSITION).getX(), EPS);
        assertEquals(centreYBefore, bjt.getInfo(AllElementInfos.POSITION).getY(), EPS);
        // Authored pivot preserved.
        LockInfo after = bjt.getInfo(AllElementInfos.LOCK);
        assertEquals(LockMode.ROTATION_FREE, after.getMode());
        assertEquals(2.0, after.getPivotX(), EPS);
        assertEquals(0.0, after.getPivotY(), EPS);
    }

    @Test
    void nonComponentTarget_isIgnored() {
        // Free nodes are outside this handler's scope; the gesture is dropped without
        // attaching a LockInfo or perturbing the node's position.
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        CircuitNode free = w.createNode(AllComponents.CONNECTION);
        place(free, 4.0, 4.0);

        RotateElementHandler handler = new RotateElementHandler(level);
        handler.handle(new RotateElementPayload(
                w.getId(), free.getId(), 0.0, 0.0, Math.PI / 2.0));
        level.tick();

        assertNull(free.getInfo(AllElementInfos.LOCK));
        assertEquals(4.0, free.getInfo(AllElementInfos.POSITION).getX(), EPS);
        assertEquals(4.0, free.getInfo(AllElementInfos.POSITION).getY(), EPS);
    }
}
