package com.minecart.display.render;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.minecart.logic.CircuitEdge;
import com.minecart.logic.CircuitNode;
import com.minecart.registry.AllElementInfos;
import com.minecart.variant.info.PositionInfo;

/**
 * Renders one {@link CircuitEdge} as its texture stretched between the {@link PositionInfo} of its two
 * endpoint nodes, rotated to align with the segment. Reads endpoint positions every frame so the visual
 * tracks node moves without bookkeeping.
 */
public class EdgeActor extends Actor {

    public static final float THICKNESS = 0.25f;

    private final CircuitEdge edge;
    private final Textures textures;

    public EdgeActor(CircuitEdge edge, Textures textures) {
        this.edge = edge;
        this.textures = textures;
    }

    public CircuitEdge getEdge() {
        return edge;
    }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        CircuitNode a = edge.getConnection(0);
        CircuitNode b = edge.getConnection(1);
        if (a == null || b == null) {
            return;
        }
        PositionInfo pa = a.getInfo(AllElementInfos.POSITION);
        PositionInfo pb = b.getInfo(AllElementInfos.POSITION);
        if (pa == null || pb == null) {
            return;
        }
        float ax = (float) pa.getX();
        float ay = (float) pa.getY();
        float bx = (float) pb.getX();
        float by = (float) pb.getY();
        float dx = bx - ax;
        float dy = by - ay;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-4f) {
            return;
        }
        float angleDeg = (float) Math.toDegrees(Math.atan2(dy, dx));
        Texture tex = textures.getById(edge.getRegistryTypeId());
        Color c = getColor();
        batch.setColor(c.r, c.g, c.b, c.a * parentAlpha);
        // Origin (0, THICKNESS/2) so the texture's left-middle anchors at endpoint A and rotates around it.
        batch.draw(tex,
                ax, ay - THICKNESS / 2f,
                0f, THICKNESS / 2f,
                len, THICKNESS,
                1f, 1f,
                angleDeg,
                0, 0,
                tex.getWidth(), tex.getHeight(),
                false, false);
        batch.setColor(1f, 1f, 1f, 1f);
    }
}
