package com.minecart.logic.cascade;

import com.minecart.elements.component.BJTransistor;
import com.minecart.event.events.RegisterElementChangeListenerEvent;
import com.minecart.foundation.Circuit;
import com.minecart.logic.CircuitElement;
import com.minecart.logic.CircuitNode;
import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;
import com.minecart.registry.AllComponents;
import com.minecart.registry.AllElementInfos;
import com.minecart.variant.info.LockInfo;
import com.minecart.variant.info.LockMode;
import com.minecart.variant.info.PositionInfo;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for {@link CombineCascadeEngine}. Focuses on the new cross-component port-on-port case
 * (translate one component, slot-swap, rewire, destroy) and the lock-preflight refusals — the
 * simple cases are covered by {@code CombineNodesTest} and exercised via the engine's
 * {@code DelegateCombineOp}.
 */
class CombineCascadeEngineTest {

    private static final double EPS = 1e-6;

    private static void place(CircuitNode n, double x, double y) {
        PositionInfo p = n.getInfo(AllElementInfos.POSITION);
        if (p == null) {
            p = new PositionInfo(x, y);
            n.setInfo(AllElementInfos.POSITION, p);
        } else {
            p.set(x, y);
        }
    }

    private static double posX(CircuitNode n) {
        return n.getInfo(AllElementInfos.POSITION).getX();
    }

    private static double posY(CircuitNode n) {
        return n.getInfo(AllElementInfos.POSITION).getY();
    }

    @Test
    void plan_freeOnFree_delegatesToCombineNodes() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        CircuitNode survivor = w.createNode(AllComponents.CONNECTION);
        CircuitNode absorbed = w.createNode(AllComponents.CONNECTION);

        CombineCascadePlan plan = CombineCascadeEngine.plan(w, survivor, absorbed);
        assertTrue(plan.isSuccess(), () -> "expected success, got " + plan.getReason());
        assertEquals(1, plan.getOps().size(), "free-on-free plans through a single delegate op");
    }

    @Test
    void tryCombine_freeOnFree_executesEndToEnd() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        CircuitNode survivor = w.createNode(AllComponents.CONNECTION);
        CircuitNode absorbed = w.createNode(AllComponents.CONNECTION);

        assertTrue(CombineCascadeEngine.tryCombine(w, survivor, absorbed));
        for (Circuit c : w.getCircuits()) {
            assertFalse(c.nodes().contains(absorbed));
        }
    }

    @Test
    void plan_crossComponentPortOnPort_movesAbsorbedComponentToBringPortToSurvivor() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        // Two transistors with explicit world placements so the translation delta is non-trivial.
        BJTransistor survivorBJT = w.createComponent(AllComponents.BJ_TRANSISTOR);
        BJTransistor absorbedBJT = w.createComponent(AllComponents.BJ_TRANSISTOR);

        // Lay survivorBJT centred at (0,0) with port 0 at (-1, 0); absorbedBJT centred at (10, 0)
        // with port 0 at (9, 0). Combine port-0 of absorbed onto port-0 of survivor → absorbedBJT
        // should translate by (-10, 0) so its port 0 sits at (-1, 0).
        survivorBJT.setInfo(AllElementInfos.POSITION, new PositionInfo(0.0, 0.0));
        place(survivorBJT.getPort(0), -1.0, 0.0);
        place(survivorBJT.getPort(1), 1.0, 0.5);
        place(survivorBJT.getPort(2), 1.0, -0.5);
        place(survivorBJT.getCenter(), 0.0, 0.0);

        absorbedBJT.setInfo(AllElementInfos.POSITION, new PositionInfo(10.0, 0.0));
        place(absorbedBJT.getPort(0), 9.0, 0.0);
        place(absorbedBJT.getPort(1), 11.0, 0.5);
        place(absorbedBJT.getPort(2), 11.0, -0.5);
        place(absorbedBJT.getCenter(), 10.0, 0.0);

        CircuitNode survivorPort = survivorBJT.getPort(0);
        CircuitNode absorbedPort = absorbedBJT.getPort(0);

        boolean ok = CombineCascadeEngine.tryCombine(w, survivorPort, absorbedPort);
        assertTrue(ok, "cross-component cascade should succeed when both can translate");

        // survivorBJT untouched: still at (0,0); its port 0 still at (-1, 0).
        assertEquals(0.0, survivorBJT.getInfo(AllElementInfos.POSITION).getX(), EPS);
        assertEquals(0.0, survivorBJT.getInfo(AllElementInfos.POSITION).getY(), EPS);
        assertEquals(-1.0, posX(survivorPort), EPS);
        assertEquals(0.0, posY(survivorPort), EPS);

        // absorbedBJT translated by (-10, 0): centre now at (0, 0); its other ports also shifted.
        assertEquals(0.0, absorbedBJT.getInfo(AllElementInfos.POSITION).getX(), EPS);
        assertEquals(0.0, absorbedBJT.getInfo(AllElementInfos.POSITION).getY(), EPS);
        assertEquals(1.0, posX(absorbedBJT.getPort(1)), EPS);
        assertEquals(0.5, posY(absorbedBJT.getPort(1)), EPS);
        assertEquals(1.0, posX(absorbedBJT.getPort(2)), EPS);
        assertEquals(-0.5, posY(absorbedBJT.getPort(2)), EPS);
        assertEquals(0.0, posX(absorbedBJT.getCenter()), EPS);
        assertEquals(0.0, posY(absorbedBJT.getCenter()), EPS);

        // Port slot 0 on absorbedBJT now holds survivorPort. survivorPort owns both components.
        assertSame(survivorPort, absorbedBJT.getPort(0));
        Set<?> owners = new HashSet<>(survivorPort.getComponents());
        assertTrue(owners.contains(survivorBJT));
        assertTrue(owners.contains(absorbedBJT));

        // absorbedPort is destroyed.
        for (Circuit c : w.getCircuits()) {
            assertFalse(c.nodes().contains(absorbedPort));
        }
    }

    @Test
    void tryCombine_crossComponentCascade_broadcastsMovedComponentAndNodes() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();

        // Capture every element handed to Level.notifyElementChanged. Register before init() so the
        // consumer is installed when the level wires up its notifier.
        Set<CircuitElement> notified = new LinkedHashSet<>();
        level.register(RegisterElementChangeListenerEvent.class, evt -> evt.register(notified::add));
        level.init();

        BJTransistor survivorBJT = w.createComponent(AllComponents.BJ_TRANSISTOR);
        BJTransistor absorbedBJT = w.createComponent(AllComponents.BJ_TRANSISTOR);

        survivorBJT.setInfo(AllElementInfos.POSITION, new PositionInfo(0.0, 0.0));
        place(survivorBJT.getPort(0), -1.0, 0.0);
        place(survivorBJT.getPort(1), 1.0, 0.5);
        place(survivorBJT.getPort(2), 1.0, -0.5);
        place(survivorBJT.getCenter(), 0.0, 0.0);

        absorbedBJT.setInfo(AllElementInfos.POSITION, new PositionInfo(10.0, 0.0));
        place(absorbedBJT.getPort(0), 9.0, 0.0);
        place(absorbedBJT.getPort(1), 11.0, 0.5);
        place(absorbedBJT.getPort(2), 11.0, -0.5);
        place(absorbedBJT.getCenter(), 10.0, 0.0);

        // Snapshot the nodes that will translate (absorbedBJT moves; its port 0 is the absorbed slot,
        // which is destroyed rather than translated).
        CircuitNode movedCenter = absorbedBJT.getCenter();
        CircuitNode movedPort1 = absorbedBJT.getPort(1);
        CircuitNode movedPort2 = absorbedBJT.getPort(2);

        notified.clear();
        boolean ok = CombineCascadeEngine.tryCombine(w, survivorBJT.getPort(0), absorbedBJT.getPort(0));
        assertTrue(ok, "cross-component cascade should succeed");

        // M3: the engine emits a batched notify for the moved component and each translated node.
        assertTrue(notified.contains(absorbedBJT), "moved component should be broadcast");
        assertTrue(notified.contains(movedCenter), "moved centre node should be broadcast");
        assertTrue(notified.contains(movedPort1), "moved port node should be broadcast");
        assertTrue(notified.contains(movedPort2), "moved port node should be broadcast");
    }

    @Test
    void plan_crossComponentPortOnPort_bothLocked_failsWithLockConflict() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor a = w.createComponent(AllComponents.BJ_TRANSISTOR);
        BJTransistor b = w.createComponent(AllComponents.BJ_TRANSISTOR);
        a.setInfo(AllElementInfos.POSITION, new PositionInfo(0.0, 0.0));
        b.setInfo(AllElementInfos.POSITION, new PositionInfo(5.0, 0.0));
        place(a.getPort(0), 0.0, 0.0);
        place(a.getPort(1), 1.0, 0.0);
        place(a.getPort(2), 1.0, 1.0);
        place(a.getCenter(), 0.0, 0.0);
        place(b.getPort(0), 5.0, 0.0);
        place(b.getPort(1), 6.0, 0.0);
        place(b.getPort(2), 6.0, 1.0);
        place(b.getCenter(), 5.0, 0.0);
        // Strict-lock both components: LOCKED disallows translation regardless of soft state.
        LockInfo aLock = new LockInfo();
        aLock.setMode(LockMode.LOCKED);
        a.setInfo(AllElementInfos.LOCK, aLock);
        LockInfo bLock = new LockInfo();
        bLock.setMode(LockMode.LOCKED);
        b.setInfo(AllElementInfos.LOCK, bLock);

        CombineCascadePlan plan = CombineCascadeEngine.plan(w, a.getPort(0), b.getPort(0));
        assertEquals(CombineCascadePlan.Reason.LOCK_CONFLICT, plan.getReason());

        // World state untouched — both ports still owned by their original components.
        assertSame(a, a.getPort(0).getComponent());
        assertSame(b, b.getPort(0).getComponent());
    }

    @Test
    void plan_crossComponentPortOnPort_onlySurvivorMovable_swapsDirection() {
        // Survivor's comp can move, absorbed's comp is locked → engine should flip roles so
        // survivor's comp is the one that translates.
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor survivorBJT = w.createComponent(AllComponents.BJ_TRANSISTOR);
        BJTransistor absorbedBJT = w.createComponent(AllComponents.BJ_TRANSISTOR);
        survivorBJT.setInfo(AllElementInfos.POSITION, new PositionInfo(0.0, 0.0));
        place(survivorBJT.getPort(0), 0.0, 0.0);
        place(survivorBJT.getPort(1), 1.0, 0.0);
        place(survivorBJT.getPort(2), 1.0, 1.0);
        place(survivorBJT.getCenter(), 0.0, 0.0);
        absorbedBJT.setInfo(AllElementInfos.POSITION, new PositionInfo(5.0, 0.0));
        place(absorbedBJT.getPort(0), 5.0, 0.0);
        place(absorbedBJT.getPort(1), 6.0, 0.0);
        place(absorbedBJT.getPort(2), 6.0, 1.0);
        place(absorbedBJT.getCenter(), 5.0, 0.0);
        // Lock only the absorbed-side component.
        LockInfo lock = new LockInfo();
        lock.setMode(LockMode.LOCKED);
        absorbedBJT.setInfo(AllElementInfos.LOCK, lock);

        CircuitNode survivorPort = survivorBJT.getPort(0); // (0,0)
        CircuitNode absorbedPort = absorbedBJT.getPort(0); // (5,0)

        boolean ok = CombineCascadeEngine.tryCombine(w, survivorPort, absorbedPort);
        assertTrue(ok);

        // Engine swapped direction: the originally-named "survivorBJT" is the one that translated
        // and absorbedBJT (the locked one) stayed put. Its port 0 is now shared.
        assertEquals(5.0, absorbedBJT.getInfo(AllElementInfos.POSITION).getX(), EPS);
        assertEquals(0.0, absorbedBJT.getInfo(AllElementInfos.POSITION).getY(), EPS);
        // survivorBJT moved by (+5, 0) so its remaining port 1/2 shifted.
        assertEquals(6.0, posX(survivorBJT.getPort(1)), EPS);
        // The surviving shared port is absorbedBJT.getPort(0) (the locked one), reachable through
        // both components. Its world position is (5, 0).
        CircuitNode shared = absorbedBJT.getPort(0);
        assertEquals(5.0, posX(shared), EPS);
        assertEquals(0.0, posY(shared), EPS);
        assertSame(shared, survivorBJT.getPort(0));
        // The originally-named survivorPort node is the one that got destroyed.
        assertNotSame(shared, survivorPort);
        for (Circuit c : w.getCircuits()) {
            assertFalse(c.nodes().contains(survivorPort));
        }
    }

    @Test
    void tryTranslateComponent_freeComponent_shiftsCentreAndAllOwnedNodes() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor bjt = w.createComponent(AllComponents.BJ_TRANSISTOR);
        bjt.setInfo(AllElementInfos.POSITION, new PositionInfo(0.0, 0.0));
        place(bjt.getPort(0), -1.0, 0.0);
        place(bjt.getPort(1), 1.0, 0.5);
        place(bjt.getCenter(), 0.0, 0.0);

        assertTrue(CombineCascadeEngine.tryTranslateComponent(w, bjt, 10.0, -3.0));
        assertEquals(10.0, bjt.getInfo(AllElementInfos.POSITION).getX(), EPS);
        assertEquals(-3.0, bjt.getInfo(AllElementInfos.POSITION).getY(), EPS);
        assertEquals(9.0, posX(bjt.getPort(0)), EPS);
        assertEquals(-3.0, posY(bjt.getPort(0)), EPS);
    }

    @Test
    void tryTranslateComponent_lockedComponent_refusesAndLeavesWorldUntouched() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor bjt = w.createComponent(AllComponents.BJ_TRANSISTOR);
        bjt.setInfo(AllElementInfos.POSITION, new PositionInfo(0.0, 0.0));
        place(bjt.getPort(0), -1.0, 0.0);
        place(bjt.getCenter(), 0.0, 0.0);
        com.minecart.variant.info.LockInfo strict = new com.minecart.variant.info.LockInfo();
        strict.setMode(com.minecart.variant.info.LockMode.LOCKED);
        bjt.setInfo(AllElementInfos.LOCK, strict);

        assertFalse(CombineCascadeEngine.tryTranslateComponent(w, bjt, 10.0, -3.0));
        // World untouched.
        assertEquals(0.0, bjt.getInfo(AllElementInfos.POSITION).getX(), EPS);
        assertEquals(-1.0, posX(bjt.getPort(0)), EPS);
    }

    @Test
    void tryRotateComponent_freeComponent_rotatesAround_pivot() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor bjt = w.createComponent(AllComponents.BJ_TRANSISTOR);
        bjt.setInfo(AllElementInfos.POSITION, new PositionInfo(0.0, 0.0));
        bjt.setInfo(AllElementInfos.ROTATION, new com.minecart.variant.info.RotationInfo(0.0));
        place(bjt.getPort(0), 1.0, 0.0);
        place(bjt.getCenter(), 0.0, 0.0);

        // Rotate by π/2 around the centre (0,0): port 0 should swing from (1, 0) to (0, 1).
        assertTrue(CombineCascadeEngine.tryRotateComponent(w, bjt, 0.0, 0.0, Math.PI / 2.0));
        assertEquals(0.0, posX(bjt.getPort(0)), 1e-9);
        assertEquals(1.0, posY(bjt.getPort(0)), 1e-9);
        assertEquals(Math.PI / 2.0, bjt.getInfo(AllElementInfos.ROTATION).getAngle(), 1e-9);
    }

    @Test
    void tryRotateComponent_positionFreeStrict_refused() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor bjt = w.createComponent(AllComponents.BJ_TRANSISTOR);
        bjt.setInfo(AllElementInfos.POSITION, new PositionInfo(0.0, 0.0));
        bjt.setInfo(AllElementInfos.ROTATION, new com.minecart.variant.info.RotationInfo(0.0));
        place(bjt.getPort(0), 1.0, 0.0);
        place(bjt.getCenter(), 0.0, 0.0);
        com.minecart.variant.info.LockInfo strict = new com.minecart.variant.info.LockInfo();
        strict.setMode(com.minecart.variant.info.LockMode.ORIENTED);
        bjt.setInfo(AllElementInfos.LOCK, strict);

        // ORIENTED allows translation but not rotation → engine refuses.
        assertFalse(CombineCascadeEngine.tryRotateComponent(w, bjt, 0.0, 0.0, Math.PI / 4.0));
        assertEquals(1.0, posX(bjt.getPort(0)), 1e-9);
        assertEquals(0.0, posY(bjt.getPort(0)), 1e-9);
        assertEquals(0.0, bjt.getInfo(AllElementInfos.ROTATION).getAngle(), 1e-9);
    }

    @Test
    void tryMovePortNode_portOfFreeComponent_translatesParent() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor bjt = w.createComponent(AllComponents.BJ_TRANSISTOR);
        bjt.setInfo(AllElementInfos.POSITION, new PositionInfo(0.0, 0.0));
        place(bjt.getPort(0), -1.0, 0.0);
        place(bjt.getPort(1), 1.0, 0.0);
        place(bjt.getCenter(), 0.0, 0.0);

        // User typed "X=2, Y=3" for port 0 in the panel → component shifts by (+3, +3) so port 0
        // ends at (2, 3) and port 1 follows from (1, 0) to (4, 3).
        assertTrue(CombineCascadeEngine.tryMovePortNode(w, bjt.getPort(0), 2.0, 3.0));
        assertEquals(2.0, posX(bjt.getPort(0)), EPS);
        assertEquals(3.0, posY(bjt.getPort(0)), EPS);
        assertEquals(4.0, posX(bjt.getPort(1)), EPS);
        assertEquals(3.0, posY(bjt.getPort(1)), EPS);
        assertEquals(3.0, bjt.getInfo(AllElementInfos.POSITION).getX(), EPS);
    }

    @Test
    void tryMovePortNode_freeNode_refused() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        CircuitNode freeNode = w.createNode(AllComponents.CONNECTION);
        place(freeNode, 0.0, 0.0);

        // tryMovePortNode is for ports; on a free node it refuses (caller writes PositionInfo
        // directly). Free node stays put.
        assertFalse(CombineCascadeEngine.tryMovePortNode(w, freeNode, 5.0, 5.0));
        assertEquals(0.0, posX(freeNode), EPS);
        assertEquals(0.0, posY(freeNode), EPS);
    }

    @Test
    void apply_rollback_onSwitchPortFailure_revertsTranslate() {
        // Build a plan manually, with a SwitchPortOp whose newNode type id doesn't match — that
        // op returns false. Engine should roll back the translate that preceded it.
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor bjt = w.createComponent(AllComponents.BJ_TRANSISTOR);
        bjt.setInfo(AllElementInfos.POSITION, new PositionInfo(3.0, 4.0));
        place(bjt.getPort(0), 2.0, 4.0);
        place(bjt.getCenter(), 3.0, 4.0);
        CircuitNode wrongType = w.createNode(AllComponents.CONNECTION);
        // BJT ports are CONNECTION too, so registry ids actually match — switchPort will succeed.
        // To force a switchPort failure, simulate by trying to put an already-port node into the
        // slot of a different index. Construct that: create a second BJT and try to switch port
        // index 0 of bjt2 to bjt1's port 0 (which is already a port elsewhere → replacePort
        // throws → switchPort returns false).
        BJTransistor bjt2 = w.createComponent(AllComponents.BJ_TRANSISTOR);
        bjt2.setInfo(AllElementInfos.POSITION, new PositionInfo(0.0, 0.0));
        place(bjt2.getPort(0), 0.0, 0.0);
        place(bjt2.getCenter(), 0.0, 0.0);
        // Sanity: same node can't appear twice in the same component's port map (replacePort
        // refuses), so try to install bjt2.getPort(1) into bjt2 slot 0. That triggers the "newPort
        // already bound to port X on the same component" guard.
        CircuitNode otherPortOfBjt2 = bjt2.getPort(1);
        TranslateComponentOp translate = new TranslateComponentOp(bjt, 100.0, 100.0);
        SwitchPortOp badSwitch = new SwitchPortOp(bjt2, 0, otherPortOfBjt2);

        boolean ok = CombineCascadeEngine.apply(w, java.util.List.of(translate, badSwitch));
        assertFalse(ok, "engine should report failure when an op returns false");
        // Translate rolled back: bjt is back at (3, 4).
        assertEquals(3.0, bjt.getInfo(AllElementInfos.POSITION).getX(), EPS);
        assertEquals(4.0, bjt.getInfo(AllElementInfos.POSITION).getY(), EPS);
        assertEquals(2.0, posX(bjt.getPort(0)), EPS);
        assertEquals(4.0, posY(bjt.getPort(0)), EPS);
    }
}
