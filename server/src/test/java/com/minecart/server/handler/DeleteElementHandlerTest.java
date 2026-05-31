package com.minecart.server.handler;

import com.minecart.elements.edge.Resistor;
import com.minecart.logic.CircuitNode;
import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;
import com.minecart.protocol.payload.client.DeleteElementPayload;
import com.minecart.registry.AllComponents;
import com.minecart.registry.AllElementInfos;
import com.minecart.variant.info.PositionInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeleteElementHandlerTest {

    private static void place(CircuitNode node, double x, double y) {
        PositionInfo p = node.getInfo(AllElementInfos.POSITION);
        if (p == null) {
            node.setInfo(AllElementInfos.POSITION, new PositionInfo(x, y));
        } else {
            p.set(x, y);
        }
    }

    @Test
    void deleteEdge_disconnectsOnlyEdgeAndKeepsEndpointNodes() {
        ServerLevel level = new ServerLevel();
        ServerWorld world = level.createWorld();
        CircuitNode a = world.createNode(AllComponents.CONNECTION);
        CircuitNode b = world.createNode(AllComponents.CONNECTION);
        place(a, 0.0, 0.0);
        place(b, 1.0, 0.0);
        Resistor edge = world.connect(AllComponents.RESISTOR, a, b);
        assertNotNull(edge);

        new DeleteElementHandler(level).handle(new DeleteElementPayload(world.getId(), edge.getId()));
        level.tick();

        assertNull(world.findElement(edge.getId()), "edge should be removed from the world");
        assertNotNull(world.findNode(a.getId()), "start node should remain");
        assertNotNull(world.findNode(b.getId()), "end node should remain");
        assertFalse(a.getConnection().contains(edge), "start node should no longer reference edge");
        assertFalse(b.getConnection().contains(edge), "end node should no longer reference edge");
    }

    @Test
    void deleteInternalComponentEdge_isIgnored() {
        ServerLevel level = new ServerLevel();
        ServerWorld world = level.createWorld();
        var component = world.createComponent(AllComponents.BJ_TRANSISTOR);
        var internalEdge = component.getEdges().iterator().next();

        new DeleteElementHandler(level).handle(new DeleteElementPayload(world.getId(), internalEdge.getId()));
        level.tick();

        assertTrue(component.getEdges().contains(internalEdge), "component-owned edge should remain owned");
        assertNotNull(world.findElement(internalEdge.getId()), "component-owned edge should remain in the world");
    }
}
