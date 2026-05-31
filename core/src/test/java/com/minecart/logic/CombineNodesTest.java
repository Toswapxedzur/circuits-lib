package com.minecart.logic;

import com.minecart.elements.component.BJTransistor;
import com.minecart.foundation.Circuit;
import com.minecart.registry.AllComponents;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for the node-combine code path in {@link ServerWorld#combineNodes} plus its building
 * blocks ({@link ServerWorld#changeEdgeEndpoint}, {@link CircuitComponent#replacePort},
 * {@link CircuitNode#canCombine}). Each test exercises a single rule so a regression points at the
 * specific invariant that broke.
 */
class CombineNodesTest {

    @Test
    void canCombine_freeNodes_acceptByDefault() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        CircuitNode a = w.createNode(AllComponents.CONNECTION);
        CircuitNode b = w.createNode(AllComponents.CONNECTION);
        assertTrue(a.canCombine(b));
        assertTrue(b.canCombine(a));
    }

    @Test
    void canCombine_intrinsicInternal_isRejected() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor bjt = w.createComponent(AllComponents.BJ_TRANSISTOR);
        CircuitNode freeNode = w.createNode(AllComponents.CONNECTION);
        // The transistor's centre is a non-port internal — its own canCombine refuses (so the
        // mutual-veto rule blocks any combine attempt). The free node's canCombine is permissive on
        // its side because it has no component to disqualify it; the rejection comes from the
        // intrinsic side, not from "anyone touching an intrinsic".
        assertFalse(bjt.getCenter().canCombine(freeNode));
        assertTrue(freeNode.canCombine(bjt.getCenter()));
        // Whole-flow combineNodes call must reject regardless of who is survivor / absorbed.
        assertFalse(w.combineNodes(freeNode, bjt.getCenter()));
        assertFalse(w.combineNodes(bjt.getCenter(), freeNode));
    }

    @Test
    void canCombine_portBehavesLikeFreeNode() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor bjt = w.createComponent(AllComponents.BJ_TRANSISTOR);
        CircuitNode freeNode = w.createNode(AllComponents.CONNECTION);
        CircuitNode base = bjt.getPort(0);
        assertTrue(base.canCombine(freeNode));
        assertTrue(freeNode.canCombine(base));
    }

    @Test
    void combineNodes_freeOntoFree_movesEdgesAndDeletesAbsorbed() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        CircuitNode survivor = w.createNode(AllComponents.CONNECTION);
        CircuitNode absorbed = w.createNode(AllComponents.CONNECTION);
        CircuitNode third = w.createNode(AllComponents.CONNECTION);
        // Connect absorbed-third with a wire so we can verify the wire follows over to survivor.
        CircuitEdge wire = w.connect(AllComponents.RESISTOR, absorbed, third);
        assertNotNull(wire);

        boolean ok = w.combineNodes(survivor, absorbed);
        assertTrue(ok);

        // Survivor + third now share a circuit; absorbed is gone from every circuit.
        assertSame(survivor.getCircuit(), third.getCircuit());
        for (Circuit c : w.getCircuits()) {
            assertFalse(c.nodes().contains(absorbed),
                    "Absorbed node should no longer live in any circuit");
        }
        // Wire still exists, repointed to survivor (replacing absorbed's slot).
        assertSame(wire, survivor.getConnection().iterator().next());
        assertTrue(wire.connectTo(survivor));
        assertTrue(wire.connectTo(third));
    }

    @Test
    void combineNodes_freeOntoFree_rejectsSelf() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        CircuitNode n = w.createNode(AllComponents.CONNECTION);
        assertFalse(w.combineNodes(n, n));
    }

    @Test
    void combineNodes_dropsSelfLoopsRatherThanCarryingThem() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        CircuitNode survivor = w.createNode(AllComponents.CONNECTION);
        CircuitNode absorbed = w.createNode(AllComponents.CONNECTION);
        // The wire connecting them would collapse to a self-loop on survivor; combine should drop it.
        CircuitEdge wire = w.connect(AllComponents.RESISTOR, survivor, absorbed);
        assertNotNull(wire);

        assertTrue(w.combineNodes(survivor, absorbed));

        assertEquals(0, survivor.getConnection().size(),
                "Self-loop wire should be dropped, not carried over");
        for (Circuit c : w.getCircuits()) {
            assertFalse(c.edges().contains(wire),
                    "Self-loop wire should no longer live in any circuit");
        }
    }

    @Test
    void combineNodes_freeOntoPort_portWinsAndKeepsAnchor() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor bjt = w.createComponent(AllComponents.BJ_TRANSISTOR);
        CircuitNode base = bjt.getPort(0);
        CircuitNode freeNode = w.createNode(AllComponents.CONNECTION);
        CircuitNode external = w.createNode(AllComponents.CONNECTION);
        // External wire attached to the FREE NODE — should reattach to the port after combine so
        // the resistor we dragged in stays wired through the transistor's anchored base.
        CircuitEdge externalWire = w.connect(AllComponents.RESISTOR, freeNode, external);
        assertNotNull(externalWire);
        // The centre↔base internal strut MUST stay anchored on the same base node, untouched by the
        // combine — that's the bug we're fixing. If anything reroutes this strut, the transistor's
        // internals come loose on disk and the user sees exposed wires after a reload.
        CircuitEdge baseStrut = null;
        for (CircuitEdge e : bjt.getEdges()) {
            if (e.connectTo(base) && e.connectTo(bjt.getCenter())) {
                baseStrut = e;
                break;
            }
        }
        assertNotNull(baseStrut, "Should find centre↔base internal strut");

        // The drag direction (survivor=free node, absorbed=port) is what the dragController
        // currently passes; combineNodes must internally swap so the port still wins.
        boolean ok = w.combineNodes(freeNode, base);
        assertTrue(ok);

        // Port slot unchanged: the original base node still occupies port 0.
        assertSame(base, bjt.getPort(0));
        assertSame(bjt, base.getComponent());
        // External wire (the resistor) re-pointed onto the port.
        assertTrue(externalWire.connectTo(base));
        // Internal strut untouched: still centre↔base.
        assertTrue(baseStrut.connectTo(base));
        assertTrue(baseStrut.connectTo(bjt.getCenter()));
        // Free node deleted from every circuit.
        for (Circuit c : w.getCircuits()) {
            assertFalse(c.nodes().contains(freeNode));
        }
    }

    @Test
    void combineNodes_freeOntoPort_callerOrderInvariantUnderSwap() {
        // Same scenario as above but passing arguments in the opposite order; result must be
        // identical because combineNodes swaps internally to enforce port-wins.
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor bjt = w.createComponent(AllComponents.BJ_TRANSISTOR);
        CircuitNode base = bjt.getPort(0);
        CircuitNode freeNode = w.createNode(AllComponents.CONNECTION);
        CircuitNode external = w.createNode(AllComponents.CONNECTION);
        CircuitEdge externalWire = w.connect(AllComponents.RESISTOR, freeNode, external);
        assertNotNull(externalWire);

        assertTrue(w.combineNodes(base, freeNode));

        assertSame(base, bjt.getPort(0));
        assertSame(bjt, base.getComponent());
        assertTrue(externalWire.connectTo(base));
        for (Circuit c : w.getCircuits()) {
            assertFalse(c.nodes().contains(freeNode));
        }
    }

    @Test
    void combineNodes_freeOntoPort_typeMismatch_stillSucceedsBecausePortStays() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor bjt = w.createComponent(AllComponents.BJ_TRANSISTOR);
        CircuitNode base = bjt.getPort(0);
        // A JUNCTION free node has a different registry id than the CONNECTION base port. Under the
        // old "free node wins" policy this combine was rejected because the port-slot-replacement
        // path required matching types. Now the port wins the slot regardless and the free node is
        // simply destroyed (its edges, if any, repoint to the port) — there's no slot mutation to
        // be type-incompatible.
        CircuitNode freeNode = w.createNode(AllComponents.JUNCTION);

        assertTrue(w.combineNodes(freeNode, base));
        // Port still points at the original base node, type unchanged.
        assertSame(base, bjt.getPort(0));
        assertSame(bjt, base.getComponent());
        // Free node is destroyed.
        for (Circuit c : w.getCircuits()) {
            assertFalse(c.nodes().contains(freeNode));
        }
    }

    @Test
    void changeEdgeEndpoint_movesIncidentSets_andFiresChangeNotification() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        CircuitNode a = w.createNode(AllComponents.CONNECTION);
        CircuitNode b = w.createNode(AllComponents.CONNECTION);
        CircuitNode c = w.createNode(AllComponents.CONNECTION);
        CircuitEdge wire = w.connect(AllComponents.RESISTOR, a, b);
        assertNotNull(wire);

        Set<CircuitEdge> notified = new HashSet<>();
        level.register(com.minecart.event.events.CircuitElementEndpointChangeEvent.class,
                evt -> notified.add(evt.getEdge()));

        boolean changed = w.changeEdgeEndpoint(wire, a, c);
        assertTrue(changed);

        assertSame(a, wire.getStart());
        assertSame(c, wire.getEnd());
        assertTrue(a.getConnection().contains(wire));
        assertTrue(c.getConnection().contains(wire));
        assertFalse(b.getConnection().contains(wire), "b should no longer reference the wire");
        assertTrue(notified.contains(wire), "EndpointChange event should fire for the edge");
    }

    @Test
    void changeEdgeEndpoint_mergesNewEndpointCircuitWithoutSplittingOldEndpoint() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        CircuitNode a = w.createNode(AllComponents.CONNECTION);
        CircuitNode b = w.createNode(AllComponents.CONNECTION);
        CircuitNode c = w.createNode(AllComponents.CONNECTION);
        CircuitEdge ab = w.connect(AllComponents.RESISTOR, a, b);
        assertNotNull(ab);
        // Right now a, b live in one circuit; c lives in its own. Repoint the wire to a-c.
        Circuit before = c.getCircuit();
        assertEquals(1, before.nodes().size());

        boolean changed = w.changeEdgeEndpoint(ab, a, c);
        assertTrue(changed);

        // a and c are now connected by the wire so their ownership containers merge. Circuits are
        // no longer split by connectivity, so b remains in the same container even after losing the
        // original edge.
        assertSame(a.getCircuit(), c.getCircuit());
        assertSame(a.getCircuit(), b.getCircuit(),
                "b should remain in the merged ownership circuit after losing its only edge");
    }

    @Test
    void changeEdgeEndpoint_keepsDisconnectedSubgraphInSameCircuit() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        // Build a — wire1 — b — wire2 — c. All in one circuit. Change wire2's start from b to a.
        // Then b becomes isolated (no other edges) and the new connectivity is a-c via wire2 plus
        // a-b via wire1, so b stays in the original circuit but loses one neighbour. Net: still
        // one connected component (a-b through wire1, a-c through wire2 → a is the hub). No split.
        CircuitNode a = w.createNode(AllComponents.CONNECTION);
        CircuitNode b = w.createNode(AllComponents.CONNECTION);
        CircuitNode c = w.createNode(AllComponents.CONNECTION);
        CircuitEdge wire1 = w.connect(AllComponents.RESISTOR, a, b);
        CircuitEdge wire2 = w.connect(AllComponents.RESISTOR, b, c);
        assertNotNull(wire1);
        assertNotNull(wire2);
        Circuit shared = a.getCircuit();
        assertEquals(3, shared.nodes().size());

        // Repoint wire2 from (b,c) to (a,c) — leaves b connected only via wire1 to a, and the whole
        // thing stays in one circuit.
        assertTrue(w.changeEdgeEndpoint(wire2, a, c));

        assertSame(shared, a.getCircuit());
        assertSame(shared, b.getCircuit());
        assertSame(shared, c.getCircuit());
        // Now repoint wire1 from (a,b) to (a,c). Leaves b with no incident edges, but the ownership
        // circuit remains intact because circuit membership is no longer strong connectivity.
        assertTrue(w.changeEdgeEndpoint(wire1, a, c));

        Set<Circuit> circuits = new HashSet<>();
        for (CircuitNode n : new CircuitNode[]{a, b, c}) {
            circuits.add(n.getCircuit());
        }
        assertEquals(1, circuits.size(), "b should stay in the same ownership circuit after losing its only edge");
    }
}
