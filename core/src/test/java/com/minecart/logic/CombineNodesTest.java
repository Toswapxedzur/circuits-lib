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
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void combineNodes_freeOntoPort_typeMatch_replacesPortAndRouteEdges() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor bjt = w.createComponent(AllComponents.BJ_TRANSISTOR);
        CircuitNode oldBase = bjt.getPort(0);
        CircuitNode survivor = w.createNode(AllComponents.CONNECTION);
        CircuitNode external = w.createNode(AllComponents.CONNECTION);
        // External wire attached to the BJT's base port — should reattach to the survivor after combine.
        CircuitEdge externalWire = w.connect(AllComponents.RESISTOR, oldBase, external);
        assertNotNull(externalWire);
        // Locate the internal strut connecting the centre to old base — its endpoint should swap to
        // the survivor when the port replacement runs.
        CircuitEdge baseStrut = null;
        for (CircuitEdge e : bjt.getEdges()) {
            if (e.connectTo(oldBase) && e.connectTo(bjt.getCenter())) {
                baseStrut = e;
                break;
            }
        }
        assertNotNull(baseStrut, "Should find centre↔base internal strut");

        boolean ok = w.combineNodes(survivor, oldBase);
        assertTrue(ok);

        // Port slot is now filled by survivor; old base node has no component pointer left.
        assertSame(survivor, bjt.getPort(0));
        assertNull(oldBase.getComponent());
        assertSame(bjt, survivor.getComponent());
        // External wire and internal strut both repointed to survivor.
        assertTrue(externalWire.connectTo(survivor));
        assertTrue(baseStrut.connectTo(survivor));
        // Old base node is deleted from every circuit.
        for (Circuit c : w.getCircuits()) {
            assertFalse(c.nodes().contains(oldBase));
        }
    }

    @Test
    void combineNodes_freeOntoPort_typeMismatch_isRejected() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor bjt = w.createComponent(AllComponents.BJ_TRANSISTOR);
        CircuitNode base = bjt.getPort(0);
        // The transistor's base port is a CONNECTION-typed node; a JUNCTION-typed node has a different
        // registry id, so the type-coherence check should refuse.
        CircuitNode survivor = w.createNode(AllComponents.JUNCTION);

        assertFalse(w.combineNodes(survivor, base));
        // Port still points at the original base node.
        assertSame(base, bjt.getPort(0));
        assertSame(bjt, base.getComponent());
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
    void changeEdgeEndpoint_mergesCircuitsWhenNewEndpointsCrossCircuits() {
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

        // a and c are now connected by the wire so they share a circuit. b lost its only edge so it
        // gets split off into its own circuit (same outcome as if we'd disconnected the original
        // a-b wire by hand).
        assertSame(a.getCircuit(), c.getCircuit());
        assertNotSame(a.getCircuit(), b.getCircuit(),
                "b should be split off into its own circuit after losing its only edge");
    }

    @Test
    void changeEdgeEndpoint_splitsCircuitWhenRemovedEndpointStrandsASubgraph() {
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
        // Now repoint wire1 from (a,b) to (a,c). Leaves b with no incident edges → b is alone in its
        // own (still-original) circuit and a/c form the other component. With seed=b we'd split off
        // {b}; with seed=a we'd split off {a, c}. Either way two components must result.
        assertTrue(w.changeEdgeEndpoint(wire1, a, c));

        Set<Circuit> circuits = new HashSet<>();
        for (CircuitNode n : new CircuitNode[]{a, b, c}) {
            circuits.add(n.getCircuit());
        }
        assertEquals(2, circuits.size(), "b should be split from a/c after losing its only edge");
    }
}
