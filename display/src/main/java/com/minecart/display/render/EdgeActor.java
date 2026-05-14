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

    /**
     * Hard upper limit on the textured body length, in case the texture has an absurd aspect ratio.
     * The natural length derived from the texture's pixel aspect ratio is clamped to this value so a
     * single edge never paints more than ~2 world units of sprite — the rest is bridged with white
     * lines regardless of how wide the source PNG happens to be.
     */
    private static final float MAX_TEXTURE_LEN = 2f;

    /**
     * Width of the white bridging line, expressed as a fraction of {@link #THICKNESS}. Slimmer than the
     * sprite so the line reads as a "wire" connector instead of mimicking the resistor / wire body.
     */
    private static final float BRIDGE_THICKNESS = THICKNESS * 0.25f;

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

        // Natural length = THICKNESS scaled by the texture's pixel aspect ratio, so the sprite always
        // paints at its true proportions (no horizontal stretch or squash). The min() against the
        // segment length keeps short edges from overshooting their endpoints — those just draw the
        // body at the segment length with zero bridging. The hard MAX_TEXTURE_LEN cap stops an unusual
        // wide texture from eating the whole segment.
        float texAspect = tex.getHeight() > 0
                ? (float) tex.getWidth() / (float) tex.getHeight()
                : 4f;
        float naturalLen = Math.min(THICKNESS * texAspect, MAX_TEXTURE_LEN);
        float bodyLen = Math.min(len, naturalLen);
        float bridgeEach = (len - bodyLen) * 0.5f;

        // Direction along the segment, in world units.
        float ux = dx / len;
        float uy = dy / len;

        // Start of the textured body (offset from A by `bridgeEach`).
        float bodyStartX = ax + ux * bridgeEach;
        float bodyStartY = ay + uy * bridgeEach;
        // End of the textured body.
        float bodyEndX = bodyStartX + ux * bodyLen;
        float bodyEndY = bodyStartY + uy * bodyLen;

        // 1) White bridge from A → body start, drawn before the body so the texture overlaps any tiny
        //    rounding gap at the join. Only when there's actually some leftover to bridge.
        if (bridgeEach > 1e-4f) {
            Texture w = textures.white();
            batch.setColor(c.r, c.g, c.b, c.a * parentAlpha);
            batch.draw(w,
                    ax, ay - BRIDGE_THICKNESS / 2f,
                    0f, BRIDGE_THICKNESS / 2f,
                    bridgeEach, BRIDGE_THICKNESS,
                    1f, 1f,
                    angleDeg,
                    0, 0,
                    w.getWidth(), w.getHeight(),
                    false, false);
            // 2) White bridge from body end → B.
            batch.draw(w,
                    bodyEndX, bodyEndY - BRIDGE_THICKNESS / 2f,
                    0f, BRIDGE_THICKNESS / 2f,
                    bridgeEach, BRIDGE_THICKNESS,
                    1f, 1f,
                    angleDeg,
                    0, 0,
                    w.getWidth(), w.getHeight(),
                    false, false);
        }

        // 3) Textured body. Origin (0, THICKNESS/2) so the texture's left-middle pins at bodyStart and the
        //    rotation pivots around it.
        batch.setColor(c.r, c.g, c.b, c.a * parentAlpha);
        batch.draw(tex,
                bodyStartX, bodyStartY - THICKNESS / 2f,
                0f, THICKNESS / 2f,
                bodyLen, THICKNESS,
                1f, 1f,
                angleDeg,
                0, 0,
                tex.getWidth(), tex.getHeight(),
                false, false);
        batch.setColor(1f, 1f, 1f, 1f);
    }
}
