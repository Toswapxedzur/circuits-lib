package com.minecart.display.render.registry.parts;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.minecart.display.render.ComponentActor;
import com.minecart.display.render.registry.RenderContext;
import com.minecart.display.render.registry.RenderPart;
import com.minecart.logic.CircuitComponent;
import com.minecart.registry.AllElementInfos;
import com.minecart.variant.info.RotationInfo;

public final class ComponentSpritePart implements RenderPart<CircuitComponent> {

    @Override
    public void layout(CircuitComponent component, RenderContext context) {
        Actor actor = context.actor();
        actor.setSize(ComponentActor.WORLD_SIZE, ComponentActor.WORLD_SIZE);
        actor.setOrigin(ComponentActor.WORLD_SIZE / 2f, ComponentActor.WORLD_SIZE / 2f);
        double[] centre = component.getVisualCenter();
        actor.setPosition((float) centre[0] - ComponentActor.WORLD_SIZE / 2f,
                (float) centre[1] - ComponentActor.WORLD_SIZE / 2f);
        RotationInfo r = component.getInfo(AllElementInfos.ROTATION);
        actor.setRotation(r != null ? (float) Math.toDegrees(r.getAngle()) : 0f);
    }

    @Override
    public void draw(CircuitComponent component, RenderContext context) {
        Texture tex = context.textures().getById(component.getRegistryTypeId());
        var batch = context.batch();
        var actor = context.actor();
        var c = context.tint();
        batch.setColor(c.r, c.g, c.b, c.a * context.parentAlpha());
        batch.draw(tex,
                actor.getX(), actor.getY(),
                actor.getOriginX(), actor.getOriginY(),
                actor.getWidth(), actor.getHeight(),
                1f, 1f,
                actor.getRotation(),
                0, 0,
                tex.getWidth(), tex.getHeight(),
                false, false);
        batch.setColor(1f, 1f, 1f, 1f);
    }
}
