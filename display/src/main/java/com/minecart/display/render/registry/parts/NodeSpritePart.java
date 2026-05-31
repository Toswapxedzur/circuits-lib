package com.minecart.display.render.registry.parts;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.minecart.display.render.NodeActor;
import com.minecart.display.render.registry.RenderContext;
import com.minecart.display.render.registry.RenderPart;
import com.minecart.logic.CircuitNode;
import com.minecart.registry.AllElementInfos;
import com.minecart.variant.info.PositionInfo;

public final class NodeSpritePart implements RenderPart<CircuitNode> {

    @Override
    public void layout(CircuitNode node, RenderContext context) {
        Actor actor = context.actor();
        actor.setSize(NodeActor.WORLD_SIZE, NodeActor.WORLD_SIZE);
        actor.setOrigin(NodeActor.WORLD_SIZE / 2f, NodeActor.WORLD_SIZE / 2f);
        PositionInfo p = node.getInfo(AllElementInfos.POSITION);
        if (p != null) {
            actor.setPosition((float) p.getX() - NodeActor.WORLD_SIZE / 2f,
                    (float) p.getY() - NodeActor.WORLD_SIZE / 2f);
        }
    }

    @Override
    public void draw(CircuitNode node, RenderContext context) {
        Texture tex = context.textures().getById(node.getRegistryTypeId());
        var batch = context.batch();
        var actor = context.actor();
        var c = context.tint();
        batch.setColor(c.r, c.g, c.b, c.a * context.parentAlpha());
        batch.draw(tex, actor.getX(), actor.getY(), NodeActor.WORLD_SIZE, NodeActor.WORLD_SIZE);
        batch.setColor(1f, 1f, 1f, 1f);
    }
}
