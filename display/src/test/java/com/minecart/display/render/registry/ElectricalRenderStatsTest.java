package com.minecart.display.render.registry;

import com.minecart.logic.CircuitEdge;
import com.minecart.logic.CircuitNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElectricalRenderStatsTest {

    @Test
    void nodeTrafficUsesOneDirectionTimesTwo() {
        CircuitNode node = new CircuitNode(null);
        CircuitNode other = new CircuitNode(null);
        CircuitEdge incoming = connected(other, node, 2.0);
        CircuitEdge outgoing = connected(node, other, 3.0);
        node.connectEdge(incoming, false);
        node.connectEdge(outgoing, false);

        assertEquals(6.0, ElectricalRenderStats.nodeTraffic(node), 1e-9);
    }

    @Test
    void edgeAndNodeScalesAreIndependent() {
        CircuitEdge edge = new CircuitEdge(null);
        edge.getCurrent().setValue(10.0);
        ElectricalRenderStats stats = new ElectricalRenderStats(10.0, 100.0);

        assertEquals(1.0f, stats.edgeIntensity(edge), 1e-6f);
        assertTrue(stats.nodeIntensity(new CircuitNode(null)) < 0.01f);
    }

    private static CircuitEdge connected(CircuitNode start, CircuitNode end, double current) {
        CircuitEdge edge = new CircuitEdge(null);
        edge.connect(start, end, false);
        edge.getCurrent().setValue(current);
        return edge;
    }
}
