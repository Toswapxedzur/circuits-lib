package com.minecart.server.handler;

import com.minecart.elements.component.BJTransistor;
import com.minecart.elements.edge.Resistor;
import com.minecart.logic.CircuitNode;
import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;
import com.minecart.protocol.payload.client.EdgeEndpointChangePayload;
import com.minecart.protocol.payload.client.MoveElementPayload;
import com.minecart.protocol.payload.client.ReplaceComponentNodePayload;
import com.minecart.registry.AllComponents;
import com.minecart.registry.AllElementInfos;
import com.minecart.variant.info.LockInfo;
import com.minecart.variant.info.LockMode;
import com.minecart.variant.info.PositionInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Coverage of the Phase 1 strict-lock enforcement added to the server-side handlers:
 * {@link MoveElementHandler}, {@link EdgeEndpointChangeHandler}, and
 * {@link ReplaceComponentNodeHandler}. Each handler used to ignore the component's / edge's
 * effective {@link com.minecart.variant.info.LockState} completely; the fix adds a preflight that
 * refuses operations forbidden by the lock state. Tests pair each handler with both a positive
 * case (lock allows the op → it lands) and a negative case (lock forbids the op → silent refuse).
 */
class Phase1LockEnforcementTest {

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
        place(bjt.getPort(0), cx - 1.0, cy);
        place(bjt.getPort(1), cx + 1.0, cy + 0.5);
        place(bjt.getPort(2), cx + 1.0, cy - 0.5);
        place(bjt.getCenter(), cx, cy);
        return bjt;
    }

    private static void setLock(BJTransistor bjt, LockMode mode) {
        // ROTATION_FREE must carry a set pivot or CircuitComponent.effectiveLockState collapses
        // it back to FREE (the "no authored pivot" fallback that lets the soft side decide). For
        // a deterministic test we always author a pivot.
        LockInfo lock = new LockInfo(mode, 0.0, 0.0, true);
        bjt.setInfo(AllElementInfos.LOCK, lock);
    }

    // ---------------------------------------------------------------------
    // MoveElementHandler — translation gate
    // ---------------------------------------------------------------------

    @Test
    void moveComponent_freeLock_isApplied() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor bjt = placeBJT(w, 0.0, 0.0);

        new MoveElementHandler(level)
                .handle(new MoveElementPayload(w.getId(), bjt.getId(), 3.0, 5.0));
        level.tick();

        // Drag landed: component centre at the requested target.
        assertEquals(3.0, bjt.getInfo(AllElementInfos.POSITION).getX(), EPS);
        assertEquals(5.0, bjt.getInfo(AllElementInfos.POSITION).getY(), EPS);
    }

    @Test
    void moveComponent_positionFreeLock_isApplied() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor bjt = placeBJT(w, 0.0, 0.0);
        setLock(bjt, LockMode.POSITION_FREE);

        new MoveElementHandler(level)
                .handle(new MoveElementPayload(w.getId(), bjt.getId(), 3.0, 5.0));
        level.tick();

        // POSITION_FREE permits translation; drag lands.
        assertEquals(3.0, bjt.getInfo(AllElementInfos.POSITION).getX(), EPS);
        assertEquals(5.0, bjt.getInfo(AllElementInfos.POSITION).getY(), EPS);
    }

    @Test
    void moveComponent_lockedStrict_isRefused() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor bjt = placeBJT(w, 1.0, 2.0);
        setLock(bjt, LockMode.LOCKED);

        new MoveElementHandler(level)
                .handle(new MoveElementPayload(w.getId(), bjt.getId(), 10.0, 10.0));
        level.tick();

        // LOCKED forbids translation; the component stays at its original centre.
        assertEquals(1.0, bjt.getInfo(AllElementInfos.POSITION).getX(), EPS);
        assertEquals(2.0, bjt.getInfo(AllElementInfos.POSITION).getY(), EPS);
    }

    @Test
    void moveComponent_rotationFreeLock_isRefused() {
        // ROTATION_FREE permits rotation but NOT translation; LockMode.allowsTranslation()
        // returns false.
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor bjt = placeBJT(w, 1.0, 2.0);
        setLock(bjt, LockMode.ROTATION_FREE);

        new MoveElementHandler(level)
                .handle(new MoveElementPayload(w.getId(), bjt.getId(), 10.0, 10.0));
        level.tick();

        assertEquals(1.0, bjt.getInfo(AllElementInfos.POSITION).getX(), EPS);
        assertEquals(2.0, bjt.getInfo(AllElementInfos.POSITION).getY(), EPS);
    }

    @Test
    void moveFreeNode_freeLock_isApplied() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        CircuitNode free = w.createNode(AllComponents.CONNECTION);
        place(free, 0.0, 0.0);

        new MoveElementHandler(level)
                .handle(new MoveElementPayload(w.getId(), free.getId(), 3.0, 4.0));
        level.tick();

        assertEquals(3.0, free.getInfo(AllElementInfos.POSITION).getX(), EPS);
        assertEquals(4.0, free.getInfo(AllElementInfos.POSITION).getY(), EPS);
    }

    @Test
    void moveFreeNode_isFixed_isRefused() {
        // Free node whose position has isFixed=true (set by upstream — e.g. a component placed it
        // and the parent linkage was lost). The existing pre-Phase-1 guard still applies.
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        CircuitNode free = w.createNode(AllComponents.CONNECTION);
        PositionInfo p = new PositionInfo(0.0, 0.0);
        p.setFixed(true);
        p.setCanChangeFix(false);
        free.setInfo(AllElementInfos.POSITION, p);

        new MoveElementHandler(level)
                .handle(new MoveElementPayload(w.getId(), free.getId(), 3.0, 4.0));
        level.tick();

        assertEquals(0.0, free.getInfo(AllElementInfos.POSITION).getX(), EPS);
        assertEquals(0.0, free.getInfo(AllElementInfos.POSITION).getY(), EPS);
    }

    // ---------------------------------------------------------------------
    // EdgeEndpointChangeHandler — topology gate
    // ---------------------------------------------------------------------

    @Test
    void changeEdgeEndpoint_freeLock_isApplied() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        CircuitNode a = w.createNode(AllComponents.CONNECTION);
        CircuitNode b = w.createNode(AllComponents.CONNECTION);
        CircuitNode c = w.createNode(AllComponents.CONNECTION);
        place(a, 0.0, 0.0);
        place(b, 1.0, 0.0);
        place(c, 2.0, 0.0);
        Resistor wire = w.connect(AllComponents.RESISTOR, a, b);
        assertNotNull(wire);

        new EdgeEndpointChangeHandler(level)
                .handle(new EdgeEndpointChangePayload(w.getId(), wire.getId(), a.getId(), c.getId()));
        level.tick();

        // Endpoint rewired: b is no longer an endpoint, c is.
        assertSame(c, wire.getEnd());
    }

    @Test
    void changeEdgeEndpoint_lockedEdge_isRefused() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        CircuitNode a = w.createNode(AllComponents.CONNECTION);
        CircuitNode b = w.createNode(AllComponents.CONNECTION);
        CircuitNode c = w.createNode(AllComponents.CONNECTION);
        place(a, 0.0, 0.0);
        place(b, 1.0, 0.0);
        place(c, 2.0, 0.0);
        Resistor wire = w.connect(AllComponents.RESISTOR, a, b);
        assertNotNull(wire);
        // Strictly lock the wire.
        LockInfo lock = new LockInfo();
        lock.setMode(LockMode.LOCKED);
        wire.setInfo(AllElementInfos.LOCK, lock);

        CircuitNode originalEnd = wire.getEnd();
        new EdgeEndpointChangeHandler(level)
                .handle(new EdgeEndpointChangePayload(w.getId(), wire.getId(), a.getId(), c.getId()));
        level.tick();

        // Strict LOCKED ⇒ topological change refused; endpoint stays put.
        assertSame(originalEnd, wire.getEnd());
        assertNotEquals(c, wire.getEnd());
    }

    // ---------------------------------------------------------------------
    // ReplaceComponentNodeHandler — port-swap gate
    // ---------------------------------------------------------------------

    @Test
    void replacePort_freeLock_isApplied() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor bjt = placeBJT(w, 0.0, 0.0);
        CircuitNode originalPort = bjt.getPort(0);
        // The replacement node must share the original port's registry type id (see the handler's
        // own consistency check). CONNECTION is the universal generic node — port(0) of BJT is a
        // CONNECTION-typed port.
        CircuitNode candidate = w.createNode(AllComponents.CONNECTION);
        place(candidate, -1.0, 0.0);

        new ReplaceComponentNodeHandler(level).handle(new ReplaceComponentNodePayload(
                w.getId(), bjt.getId(), originalPort.getId(), candidate.getId()));
        level.tick();

        // Port was actually swapped.
        assertSame(candidate, bjt.getPort(0));
    }

    @Test
    void replacePort_lockedComponent_isRefused() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor bjt = placeBJT(w, 0.0, 0.0);
        setLock(bjt, LockMode.LOCKED);

        CircuitNode originalPort = bjt.getPort(0);
        CircuitNode candidate = w.createNode(AllComponents.CONNECTION);
        place(candidate, -1.0, 0.0);

        new ReplaceComponentNodeHandler(level).handle(new ReplaceComponentNodePayload(
                w.getId(), bjt.getId(), originalPort.getId(), candidate.getId()));
        level.tick();

        // Strict LOCKED on the component ⇒ no port swap.
        assertSame(originalPort, bjt.getPort(0));
    }
}
