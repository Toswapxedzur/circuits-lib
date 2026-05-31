package com.minecart.display.render.bounds;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.minecart.logic.CircuitNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundingBoxRegistryTest {

    @Test
    void nodeFallbackUsesActorBounds() {
        CircuitNode node = new CircuitNode(null);
        node.setRegistryTypeId("test:node");
        Actor actor = new Actor();
        actor.setBounds(10f, 20f, 2f, 3f);

        assertTrue(BoundingBoxRegistry.contains(node, actor, 11f, 22f));
        assertFalse(BoundingBoxRegistry.contains(node, actor, 13f, 22f));
    }
}
