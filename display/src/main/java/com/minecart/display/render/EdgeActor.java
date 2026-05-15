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

    public static final float THICKNESS = 1f;

    /**
     * Hard upper limit on the textured body length, in case the texture has an absurd aspect ratio.
     * The natural length derived from the texture's pixel aspect ratio is clamped to this value so a
     * single edge never paints more than ~2 world units of body sprite — anything beyond becomes wire
     * bridges (tiled by {@link #drawTiledBridge}) regardless of how wide the source PNG happens to be.
     */
    private static final float MAX_TEXTURE_LEN = 2f;

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

        // Bridges (line extensions) are tiled from a separate wire texture chosen by the
        // {@link WireTextureRegistry}. Each tile draws the wire sprite at its native aspect
        // ({@code tileLen = THICKNESS * wireAspect} long, {@code THICKNESS} thick) — no horizontal
        // stretching anywhere — and the very last tile on each bridge is source-rect-clipped so an
        // arbitrary segment length lands flush at the endpoint without distorting the sprite.
        //
        // Tiles march OUTWARD from the body toward the endpoint, so the partial cut sits at the node
        // side where the node sprite (drawn on a higher layer) hides the seam. The full-tile edges butt
        // against the body sprite; the body's own draw call below paints over any sub-pixel seam there.
        if (bridgeEach > 1e-4f) {
            Texture wireTex = textures.getById(WireTextureRegistry.get().lookup(edge));
            float wireAspect = wireTex.getHeight() > 0
                    ? (float) wireTex.getWidth() / (float) wireTex.getHeight()
                    : 4f;
            float tileLen = THICKNESS * wireAspect;
            // body -> A (negative direction along the segment).
            drawTiledBridge(batch, wireTex, bodyStartX, bodyStartY, -ux, -uy,
                    bridgeEach, tileLen, c, parentAlpha);
            // body -> B (positive direction).
            drawTiledBridge(batch, wireTex, bodyEndX, bodyEndY, ux, uy,
                    bridgeEach, tileLen, c, parentAlpha);
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

    /**
     * Tiles {@code tex} along the unit direction {@code (dirX, dirY)} starting at world point
     * {@code (startX, startY)} for {@code totalLen} world units. Each tile is drawn at its natural aspect
     * ({@code tileLen} long × {@link #THICKNESS} thick); the trailing partial tile is source-rect-clipped
     * so the destination width matches the leftover length without horizontally squashing the texture.
     *
     * <p>Each {@code batch.draw} call uses the long-form overload: the destination quad's
     * pre-rotation rectangle is {@code [ox, ox+w] × [oy - THICKNESS/2, oy + THICKNESS/2]}, with the
     * rotation pivot pinned at {@code (ox, oy)} via {@code originX/Y = (0, THICKNESS/2)}. The rotation
     * itself is {@code atan2(dirY, dirX)} so "+X before rotation" maps to the {@code (dirX, dirY)}
     * direction in world space — this is how the body→A bridge gets to extend in the {@code -segment}
     * direction by passing {@code (-ux, -uy)} as the direction.
     */
    private static void drawTiledBridge(Batch batch, Texture tex,
            float startX, float startY, float dirX, float dirY,
            float totalLen, float tileLen,
            Color c, float parentAlpha) {
        if (tileLen <= 1e-4f) {
            return;
        }
        // Direction-derived angle so positive direction means "extend the way (dirX, dirY) points".
        // For body->A bridges this is segment angle + 180, putting tiles on the A side of the body.
        float bridgeAngleDeg = (float) Math.toDegrees(Math.atan2(dirY, dirX));

        int fullTiles = (int) Math.floor(totalLen / tileLen);
        float remainder = totalLen - fullTiles * tileLen;

        batch.setColor(c.r, c.g, c.b, c.a * parentAlpha);

        for (int i = 0; i < fullTiles; i++) {
            float ox = startX + dirX * (tileLen * i);
            float oy = startY + dirY * (tileLen * i);
            batch.draw(tex,
                    ox, oy - THICKNESS / 2f,
                    0f, THICKNESS / 2f,
                    tileLen, THICKNESS,
                    1f, 1f,
                    bridgeAngleDeg,
                    0, 0,
                    tex.getWidth(), tex.getHeight(),
                    false, false);
        }

        if (remainder > 1e-4f) {
            float ox = startX + dirX * (tileLen * fullTiles);
            float oy = startY + dirY * (tileLen * fullTiles);
            // Source width scales linearly with destination width so the texture's horizontal pixel
            // density (and therefore the visible stripe profile) stays at native scale across the cut.
            int srcW = Math.max(1, Math.round(tex.getWidth() * (remainder / tileLen)));
            batch.draw(tex,
                    ox, oy - THICKNESS / 2f,
                    0f, THICKNESS / 2f,
                    remainder, THICKNESS,
                    1f, 1f,
                    bridgeAngleDeg,
                    0, 0,
                    srcW, tex.getHeight(),
                    false, false);
        }
    }
}
