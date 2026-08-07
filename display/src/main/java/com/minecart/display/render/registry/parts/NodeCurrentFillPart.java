package com.minecart.display.render.registry.parts;

import com.minecart.display.render.NodeActor;
import com.minecart.display.render.registry.CurrentRenderColors;
import com.minecart.display.render.registry.RenderContext;
import com.minecart.display.render.registry.RenderPart;
import com.minecart.logic.CircuitNode;

/** Inner node fill colored by log-scaled total traffic through the node. */
public final class NodeCurrentFillPart implements RenderPart<CircuitNode> {

    private static final float INNER_SIZE = NodeActor.WORLD_SIZE * 0.42f;

    @Override
    public void draw(CircuitNode node, RenderContext context) {
        var actor = context.actor();
        var pixel = context.textures().whitePixel();
        var color = CurrentRenderColors.colorFor(context.electricalStats().nodeIntensity(node), 0.88f);
        float x = actor.getX() + (actor.getWidth() - INNER_SIZE) * 0.5f;
        float y = actor.getY() + (actor.getHeight() - INNER_SIZE) * 0.5f;
        var batch = context.batch();
        batch.setColor(color.r, color.g, color.b, color.a * context.parentAlpha());
        batch.draw(pixel, x, y, INNER_SIZE, INNER_SIZE);
        batch.setColor(1f, 1f, 1f, 1f);
    }
}
