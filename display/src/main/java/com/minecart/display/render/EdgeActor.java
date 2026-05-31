package com.minecart.display.render;

import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.minecart.display.render.registry.RenderRegistry;
import com.minecart.logic.CircuitEdge;

/**
 * Scene2D adapter for one {@link CircuitEdge}. Drawing is delegated to the render registry.
 */
public class EdgeActor extends Actor {

    public static final float THICKNESS = 1f;

    /**
     * Hard upper limit on the textured body length, in case the texture has an absurd aspect ratio.
     * The natural length derived from the texture's pixel aspect ratio is clamped to this value so a
     * single edge never paints more than ~2 world units of body sprite — anything beyond becomes wire
     * bridges regardless of how wide the source PNG happens to be.
     */
    public static final float MAX_TEXTURE_LEN = 2f;

    private final CircuitEdge edge;

    public EdgeActor(CircuitEdge edge, Textures textures) {
        this.edge = edge;
    }

    public CircuitEdge getEdge() {
        return edge;
    }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        RenderRegistry.draw(edge, ((WorldStage) getStage()).createRenderContext(edge, this, batch, parentAlpha));
    }
}
