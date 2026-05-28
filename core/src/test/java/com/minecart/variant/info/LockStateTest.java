package com.minecart.variant.info;

import com.minecart.elements.component.BJTransistor;
import com.minecart.logic.CircuitNode;
import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;
import com.minecart.registry.AllComponents;
import com.minecart.registry.AllElementInfos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for the lock-state data model: the {@link LockMode} AND lattice, pivot reconciliation in
 * {@link LockState}, soft derivation on {@link com.minecart.logic.CircuitComponent} and
 * {@link com.minecart.logic.CircuitEdge}, and the effective-lock combiner. The cascade engine that
 * consumes these results lives one layer up; this suite is the data-layer regression net.
 */
class LockStateTest {

    private static final double EPS = 1e-6;

    @Test
    void lockMode_andTable_pairwise() {
        // Self-AND is the identity element (operationally: same constraint twice = same constraint).
        for (LockMode m : LockMode.values()) {
            assertEquals(m, LockMode.and(m, m), "self-and " + m);
        }
        // FREE acts as identity.
        for (LockMode m : LockMode.values()) {
            assertEquals(m, LockMode.and(LockMode.FREE, m));
            assertEquals(m, LockMode.and(m, LockMode.FREE));
        }
        // LOCKED absorbs.
        for (LockMode m : LockMode.values()) {
            assertEquals(LockMode.LOCKED, LockMode.and(LockMode.LOCKED, m));
            assertEquals(LockMode.LOCKED, LockMode.and(m, LockMode.LOCKED));
        }
        // POSITION_FREE ∩ ROTATION_FREE = LOCKED: their operation sets are disjoint.
        assertEquals(LockMode.LOCKED,
                LockMode.and(LockMode.POSITION_FREE, LockMode.ROTATION_FREE));
        assertEquals(LockMode.LOCKED,
                LockMode.and(LockMode.ROTATION_FREE, LockMode.POSITION_FREE));
    }

    @Test
    void lockMode_forNode_collapsesRotationModes() {
        assertEquals(LockMode.FREE, LockMode.FREE.forNode());
        assertEquals(LockMode.FREE, LockMode.POSITION_FREE.forNode(),
                "Nodes have no rotation, so POSITION_FREE has the same operation set as FREE");
        assertEquals(LockMode.LOCKED, LockMode.ROTATION_FREE.forNode(),
                "Nodes have no rotation, so a rotation-only freedom = no motion");
        assertEquals(LockMode.LOCKED, LockMode.LOCKED.forNode());
    }

    @Test
    void lockState_and_rotationFree_pivotsMatch_keepsRotationFree() {
        LockState a = LockState.rotationFree(5.0, 3.0);
        LockState b = LockState.rotationFree(5.0, 3.0);
        LockState combined = LockState.and(a, b, EPS);
        assertEquals(LockMode.ROTATION_FREE, combined.mode());
        assertEquals(5.0, combined.pivotX(), EPS);
        assertEquals(3.0, combined.pivotY(), EPS);
        assertTrue(combined.pivotValid());
    }

    @Test
    void lockState_and_rotationFree_pivotsDisagree_collapseToLocked() {
        LockState a = LockState.rotationFree(0.0, 0.0);
        LockState b = LockState.rotationFree(1.0, 1.0);
        LockState combined = LockState.and(a, b, EPS);
        assertEquals(LockMode.LOCKED, combined.mode(),
                "Two rotation-only freedoms with different pivots can't be satisfied at once");
    }

    @Test
    void lockState_and_freeBesidesRotationFree_keepsTheRotationPivot() {
        // FREE ∩ ROTATION_FREE = ROTATION_FREE; the only available pivot is the one carried on the
        // ROTATION_FREE side, so the result must carry it through.
        LockState free = LockState.FREE;
        LockState rot = LockState.rotationFree(7.0, -2.0);
        LockState combined = LockState.and(free, rot, EPS);
        assertEquals(LockMode.ROTATION_FREE, combined.mode());
        assertEquals(7.0, combined.pivotX(), EPS);
        assertEquals(-2.0, combined.pivotY(), EPS);
    }

    @Test
    void lockState_and_positionFreeAndRotationFree_lockedRegardlessOfPivots() {
        LockState a = LockState.positionFree();
        LockState b = LockState.rotationFree(5.0, 5.0);
        assertEquals(LockMode.LOCKED, LockState.and(a, b, EPS).mode());
        assertEquals(LockMode.LOCKED, LockState.and(b, a, EPS).mode());
    }

    @Test
    void component_softLock_noLockedPorts_returnsFreeWithCentrePivot() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor bjt = w.createComponent(AllComponents.BJ_TRANSISTOR);
        // Place a centre PositionInfo so the default pivot has somewhere to land.
        bjt.setInfo(AllElementInfos.POSITION, new PositionInfo(12.0, 34.0));
        // No ports locked yet — soft is FREE with the centre as the rotation-default pivot.
        LockState soft = bjt.getSoftLockState();
        assertEquals(LockMode.FREE, soft.mode());
        assertEquals(12.0, soft.pivotX(), EPS);
        assertEquals(34.0, soft.pivotY(), EPS);
        assertTrue(soft.pivotValid(),
                "A FREE component with a centre PositionInfo should advertise the centre as the "
                        + "default rotation pivot so gestures don't have to recompute it");
    }

    @Test
    void component_softLock_oneLockedPort_returnsRotationFreeAtThatPort() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor bjt = w.createComponent(AllComponents.BJ_TRANSISTOR);
        bjt.setInfo(AllElementInfos.POSITION, new PositionInfo(0.0, 0.0));
        CircuitNode base = bjt.getPort(0);
        base.setInfo(AllElementInfos.POSITION, new PositionInfo(2.5, -1.5));
        base.getInfo(AllElementInfos.POSITION).setFixed(true);

        LockState soft = bjt.getSoftLockState();
        assertEquals(LockMode.ROTATION_FREE, soft.mode());
        assertEquals(2.5, soft.pivotX(), EPS);
        assertEquals(-1.5, soft.pivotY(), EPS);
    }

    @Test
    void component_softLock_twoLockedPorts_returnsLocked() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor bjt = w.createComponent(AllComponents.BJ_TRANSISTOR);
        for (int i = 0; i < 2; i++) {
            CircuitNode p = bjt.getPort(i);
            p.setInfo(AllElementInfos.POSITION, new PositionInfo(i, i));
            p.getInfo(AllElementInfos.POSITION).setFixed(true);
        }
        assertEquals(LockMode.LOCKED, bjt.getSoftLockState().mode());
    }

    @Test
    void component_effectiveLock_strictPositionFree_andSoftFree_isPositionFree() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor bjt = w.createComponent(AllComponents.BJ_TRANSISTOR);
        bjt.setInfo(AllElementInfos.POSITION, new PositionInfo(0.0, 0.0));
        LockInfo strict = new LockInfo();
        strict.setMode(LockMode.POSITION_FREE);
        bjt.setInfo(AllElementInfos.LOCK, strict);
        // Nothing locked on the soft side, so soft = FREE; FREE ∩ POSITION_FREE = POSITION_FREE.
        assertEquals(LockMode.POSITION_FREE, bjt.effectiveLockState(EPS).mode());
    }

    @Test
    void component_effectiveLock_strictPositionFree_andSoftRotationFree_isLocked() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor bjt = w.createComponent(AllComponents.BJ_TRANSISTOR);
        bjt.setInfo(AllElementInfos.POSITION, new PositionInfo(0.0, 0.0));
        CircuitNode base = bjt.getPort(0);
        base.setInfo(AllElementInfos.POSITION, new PositionInfo(1.0, 0.0));
        base.getInfo(AllElementInfos.POSITION).setFixed(true);
        LockInfo strict = new LockInfo();
        strict.setMode(LockMode.POSITION_FREE);
        bjt.setInfo(AllElementInfos.LOCK, strict);
        // soft = ROTATION_FREE (one port locked); strict = POSITION_FREE. Disjoint → LOCKED.
        assertEquals(LockMode.LOCKED, bjt.effectiveLockState(EPS).mode());
    }

    @Test
    void lockInfo_strictLock_cannotChangeModeWhenImmutable() {
        LockInfo info = new LockInfo();
        info.setMutableByPlayer(false);
        assertFalse(info.setMode(LockMode.LOCKED),
                "Immutable LockInfo should refuse mode changes (strict-locked component case)");
        assertEquals(LockMode.FREE, info.getMode());
    }
}
