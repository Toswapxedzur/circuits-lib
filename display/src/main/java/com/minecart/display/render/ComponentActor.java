package com.minecart.display.render;

import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.minecart.display.render.registry.RenderRegistry;
import com.minecart.logic.CircuitComponent;

/**
 * Scene2D adapter for one {@link CircuitComponent}. Layout and drawing are delegated to the render registry.
 */
public class ComponentActor extends Actor {

    public static final float WORLD_SIZE = 2.0f;

    private final CircuitComponent component;

    public ComponentActor(CircuitComponent component, Textures textures) {
        this.component = component;
        setSize(WORLD_SIZE, WORLD_SIZE);
        setOrigin(WORLD_SIZE / 2f, WORLD_SIZE / 2f);
    }

    public CircuitComponent getComponent() {
        return component;
    }

    @Override
    public void act(float delta) {
        super.act(delta);
        RenderRegistry.layout(component, ((WorldStage) getStage()).createRenderContext(component, this, null, 1f));
    }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        RenderRegistry.draw(component, ((WorldStage) getStage()).createRenderContext(component, this, batch, parentAlpha));
    }
}
