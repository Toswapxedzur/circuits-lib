package com.minecart.display.render;

import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.minecart.display.render.registry.RenderRegistry;
import com.minecart.logic.CircuitNode;

/**
 * Scene2D adapter for one {@link CircuitNode}. Layout and drawing are delegated to the render registry.
 */
public class NodeActor extends Actor {

    public static final float WORLD_SIZE = 0.6f;

    private final CircuitNode node;

    public NodeActor(CircuitNode node, Textures textures) {
        this.node = node;
        setSize(WORLD_SIZE, WORLD_SIZE);
        setOrigin(WORLD_SIZE / 2f, WORLD_SIZE / 2f);
    }

    public CircuitNode getNode() {
        return node;
    }

    @Override
    public void act(float delta) {
        super.act(delta);
        RenderRegistry.layout(node, ((WorldStage) getStage()).createRenderContext(node, this, null, 1f));
    }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        RenderRegistry.draw(node, ((WorldStage) getStage()).createRenderContext(node, this, batch, parentAlpha));
    }
}
