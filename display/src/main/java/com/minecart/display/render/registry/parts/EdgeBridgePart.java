package com.minecart.display.render.registry.parts;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.minecart.display.render.EdgeActor;
import com.minecart.display.render.WireTextureRegistry;
import com.minecart.display.render.registry.RenderContext;
import com.minecart.display.render.registry.RenderPart;
import com.minecart.logic.CircuitEdge;

public final class EdgeBridgePart implements RenderPart<CircuitEdge> {

    @Override
    public void draw(CircuitEdge edge, RenderContext context) {
        Texture bodyTex = context.textures().getById(edge.getRegistryTypeId());
        EdgeGeometry g = EdgeGeometry.of(edge, EdgeGeometry.naturalBodyLength(bodyTex));
        if (g == null || g.bridgeEach <= 1e-4f) {
            return;
        }
        Texture wireTex = context.textures().getById(WireTextureRegistry.get().lookup(edge));
        float wireAspect = wireTex.getHeight() > 0
                ? (float) wireTex.getWidth() / (float) wireTex.getHeight()
                : 4f;
        float tileLen = EdgeActor.THICKNESS * wireAspect;
        drawTiledBridge(context.batch(), wireTex, g.bodyStartX, g.bodyStartY, -g.ux, -g.uy,
                g.bridgeEach, tileLen, context);
        drawTiledBridge(context.batch(), wireTex, g.bodyEndX, g.bodyEndY, g.ux, g.uy,
                g.bridgeEach, tileLen, context);
    }

    private static void drawTiledBridge(Batch batch, Texture tex,
            float startX, float startY, float dirX, float dirY,
            float totalLen, float tileLen, RenderContext context) {
        if (tileLen <= 1e-4f) {
            return;
        }
        float bridgeAngleDeg = (float) Math.toDegrees(Math.atan2(dirY, dirX));
        int fullTiles = (int) Math.floor(totalLen / tileLen);
        float remainder = totalLen - fullTiles * tileLen;
        var c = context.tint();
        batch.setColor(c.r, c.g, c.b, c.a * context.parentAlpha());
        for (int i = 0; i < fullTiles; i++) {
            float ox = startX + dirX * (tileLen * i);
            float oy = startY + dirY * (tileLen * i);
            batch.draw(tex,
                    ox, oy - EdgeActor.THICKNESS / 2f,
                    0f, EdgeActor.THICKNESS / 2f,
                    tileLen, EdgeActor.THICKNESS,
                    1f, 1f,
                    bridgeAngleDeg,
                    0, 0,
                    tex.getWidth(), tex.getHeight(),
                    false, false);
        }
        if (remainder > 1e-4f) {
            float ox = startX + dirX * (tileLen * fullTiles);
            float oy = startY + dirY * (tileLen * fullTiles);
            int srcW = Math.max(1, Math.round(tex.getWidth() * (remainder / tileLen)));
            batch.draw(tex,
                    ox, oy - EdgeActor.THICKNESS / 2f,
                    0f, EdgeActor.THICKNESS / 2f,
                    remainder, EdgeActor.THICKNESS,
                    1f, 1f,
                    bridgeAngleDeg,
                    0, 0,
                    srcW, tex.getHeight(),
                    false, false);
        }
        batch.setColor(1f, 1f, 1f, 1f);
    }
}
