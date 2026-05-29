package com.minecart.server.handler;

import com.minecart.elements.component.BJTransistor;
import com.minecart.elements.edge.Resistor;
import com.minecart.event.events.RegisterElementChangeListenerEvent;
import com.minecart.logic.CircuitElement;
import com.minecart.logic.CircuitNode;
import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;
import com.minecart.protocol.payload.client.EdgeEndpointChangePayload;
import com.minecart.protocol.payload.client.MoveElementPayload;
import com.minecart.protocol.payload.client.ReplaceComponentNodePayload;
import com.minecart.protocol.payload.client.RotateElementPayload;
import com.minecart.registry.AllComponents;
import com.minecart.registry.AllElementInfos;
import com.minecart.variant.info.LockInfo;
import com.minecart.variant.info.LockMode;
import com.minecart.variant.info.PositionInfo;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /**
     * Attaches a recorder for {@link com.minecart.foundation.Level#notifyElementChanged} on the
     * supplied level. Must be called BEFORE the level is initialised (i.e. before the first
     * {@code level.tick()}), because {@code init()} freezes the listener set. Returns a mutable
     * set the test asserts against after the gesture has been processed.
     */
    private static Set<UUID> attachChangeRecorder(ServerLevel level) {
        Set<UUID> changed = new LinkedHashSet<>();
        level.register(RegisterElementChangeListenerEvent.class,
                e -> e.register((CircuitElement el) -> changed.add(el.getId())));
        return changed;
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

    // ---------------------------------------------------------------------
    // Refusal rebroadcast — server must push the authoritative pose back at clients on every
    // refused gesture so the client's optimistic mid-drag mutations get corrected. Without this,
    // a LOCKED component still appears to move on the client because the server silently no-ops
    // and never sends the sync delta that would snap the visual back.
    // ---------------------------------------------------------------------

    @Test
    void moveComponent_lockedStrict_refusalBroadcastsAuthoritativePose() {
        ServerLevel level = new ServerLevel();
        Set<UUID> changed = attachChangeRecorder(level);
        ServerWorld w = level.createWorld();
        BJTransistor bjt = placeBJT(w, 1.0, 2.0);
        setLock(bjt, LockMode.LOCKED);
        // First tick swallows the post-create insert notifications so we can isolate the
        // refusal-broadcast set in the assertion below.
        level.tick();
        changed.clear();

        new MoveElementHandler(level)
                .handle(new MoveElementPayload(w.getId(), bjt.getId(), 10.0, 10.0));
        level.tick();

        // Server-side state still authoritative (test is in Phase1 already verified): position
        // unchanged. What's new here is the refusal rebroadcast: the BJT *and* every internal
        // port must have been notified so the sync layer pushes a CHANGE delta for each. Without
        // this, a client that optimistically translated the body would still display it at the
        // dragged offset.
        assertEquals(1.0, bjt.getInfo(AllElementInfos.POSITION).getX(), EPS);
        assertEquals(2.0, bjt.getInfo(AllElementInfos.POSITION).getY(), EPS);
        assertTrue(changed.contains(bjt.getId()),
                "refused move must notify the component itself for sync rebroadcast");
        for (CircuitNode n : bjt.getNodes()) {
            assertTrue(changed.contains(n.getId()),
                    "refused move must notify each internal node so the client snaps ports back");
        }
    }

    @Test
    void rotateComponent_lockedStrict_refusalBroadcastsAuthoritativePose() {
        ServerLevel level = new ServerLevel();
        Set<UUID> changed = attachChangeRecorder(level);
        ServerWorld w = level.createWorld();
        BJTransistor bjt = placeBJT(w, 0.0, 0.0);
        setLock(bjt, LockMode.LOCKED);
        level.tick();
        changed.clear();

        new RotateElementHandler(level)
                .handle(new RotateElementPayload(w.getId(), bjt.getId(), 0.0, 0.0, Math.PI / 4.0));
        level.tick();

        // Rotation refused; original angle preserved AND the rebroadcast fired.
        assertTrue(changed.contains(bjt.getId()),
                "refused rotation must notify the component for sync rebroadcast");
        for (CircuitNode n : bjt.getNodes()) {
            assertTrue(changed.contains(n.getId()),
                    "refused rotation must notify each internal node so the client un-orbits ports");
        }
    }

    @Test
    void moveFreeNode_isFixed_refusalBroadcastsAuthoritativePose() {
        ServerLevel level = new ServerLevel();
        Set<UUID> changed = attachChangeRecorder(level);
        ServerWorld w = level.createWorld();
        CircuitNode free = w.createNode(AllComponents.CONNECTION);
        PositionInfo p = new PositionInfo(0.0, 0.0);
        p.setFixed(true);
        p.setCanChangeFix(false);
        free.setInfo(AllElementInfos.POSITION, p);
        level.tick();
        changed.clear();

        new MoveElementHandler(level)
                .handle(new MoveElementPayload(w.getId(), free.getId(), 3.0, 4.0));
        level.tick();

        assertTrue(changed.contains(free.getId()),
                "refused free-node move must notify the node so the client snaps back");
    }

    @Test
    void changeEdgeEndpoint_lockedEdge_refusalBroadcastsAuthoritativePose() {
        ServerLevel level = new ServerLevel();
        Set<UUID> changed = attachChangeRecorder(level);
        ServerWorld w = level.createWorld();
        CircuitNode a = w.createNode(AllComponents.CONNECTION);
        CircuitNode b = w.createNode(AllComponents.CONNECTION);
        CircuitNode c = w.createNode(AllComponents.CONNECTION);
        place(a, 0.0, 0.0);
        place(b, 1.0, 0.0);
        place(c, 2.0, 0.0);
        Resistor wire = w.connect(AllComponents.RESISTOR, a, b);
        assertNotNull(wire);
        LockInfo lock = new LockInfo();
        lock.setMode(LockMode.LOCKED);
        wire.setInfo(AllElementInfos.LOCK, lock);
        level.tick();
        changed.clear();

        new EdgeEndpointChangeHandler(level)
                .handle(new EdgeEndpointChangePayload(w.getId(), wire.getId(), a.getId(), c.getId()));
        level.tick();

        // Edge stayed put; the rebroadcast set must include the edge and both old endpoints so a
        // client that previewed the rewire snaps every affected mirror back at once.
        assertSame(b, wire.getEnd());
        assertTrue(changed.contains(wire.getId()), "refused edge rewire must notify the edge");
        assertTrue(changed.contains(a.getId()), "refused edge rewire must notify the old start");
        assertTrue(changed.contains(b.getId()), "refused edge rewire must notify the old end");
    }

    @Test
    void replacePort_lockedComponent_refusalBroadcastsAuthoritativePose() {
        ServerLevel level = new ServerLevel();
        Set<UUID> changed = attachChangeRecorder(level);
        ServerWorld w = level.createWorld();
        BJTransistor bjt = placeBJT(w, 0.0, 0.0);
        setLock(bjt, LockMode.LOCKED);
        CircuitNode originalPort = bjt.getPort(0);
        CircuitNode candidate = w.createNode(AllComponents.CONNECTION);
        place(candidate, -1.0, 0.0);
        level.tick();
        changed.clear();

        new ReplaceComponentNodeHandler(level).handle(new ReplaceComponentNodePayload(
                w.getId(), bjt.getId(), originalPort.getId(), candidate.getId()));
        level.tick();

        // No swap; rebroadcast covers the component plus both candidates so the client snaps any
        // mid-flight optimistic UI back. The candidate broadcast in particular matters when the
        // client has dragged the candidate node onto the port slot and is showing it visually
        // attached: the snapshot pushes its real (un-attached) position back over the optimism.
        assertSame(originalPort, bjt.getPort(0));
        assertTrue(changed.contains(bjt.getId()), "refused port swap must notify the component");
        assertTrue(changed.contains(originalPort.getId()),
                "refused port swap must notify the original port node");
        assertTrue(changed.contains(candidate.getId()),
                "refused port swap must notify the candidate node");
    }
}
