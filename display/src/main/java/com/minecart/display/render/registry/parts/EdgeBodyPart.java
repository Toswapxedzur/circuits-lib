package com.minecart.display.render.registry.parts;

import com.badlogic.gdx.graphics.Texture;
import com.minecart.display.render.EdgeActor;
import com.minecart.display.render.registry.RenderContext;
import com.minecart.display.render.registry.RenderPart;
import com.minecart.logic.CircuitEdge;

public final class EdgeBodyPart implements RenderPart<CircuitEdge> {

    @Override
    public void draw(CircuitEdge edge, RenderContext context) {
        Texture tex = context.textures().getById(edge.getRegistryTypeId());
        EdgeGeometry g = EdgeGeometry.of(edge, EdgeGeometry.naturalBodyLength(tex));
        if (g == null) {
            return;
        }
        var batch = context.batch();
        var c = context.tint();
        batch.setColor(c.r, c.g, c.b, c.a * context.parentAlpha());
        batch.draw(tex,
                g.bodyStartX, g.bodyStartY - EdgeActor.THICKNESS / 2f,
                0f, EdgeActor.THICKNESS / 2f,
                g.bodyLen, EdgeActor.THICKNESS,
                1f, 1f,
                g.angleDeg,
                0, 0,
                tex.getWidth(), tex.getHeight(),
                false, false);
        batch.setColor(1f, 1f, 1f, 1f);
    }
}
