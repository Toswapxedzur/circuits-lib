package com.minecart.display.render;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.minecart.logic.CircuitComponent;
import com.minecart.registry.AllElementInfos;
import com.minecart.variant.info.PositionInfo;
import com.minecart.variant.info.RotationInfo;

/**
 * Renders one {@link CircuitComponent} as its texture centred on its {@link PositionInfo} and rotated by
 * its {@link RotationInfo} (radians). Pose is read every frame so server-driven moves/rotations animate
 * without explicit bookkeeping.
 */
public class ComponentActor extends Actor {

    public static final float WORLD_SIZE = 2.0f;

    private final CircuitComponent component;
    private final Textures textures;

    public ComponentActor(CircuitComponent component, Textures textures) {
        this.component = component;
        this.textures = textures;
        setSize(WORLD_SIZE, WORLD_SIZE);
        setOrigin(WORLD_SIZE / 2f, WORLD_SIZE / 2f);
    }

    public CircuitComponent getComponent() {
        return component;
    }

    @Override
    public void act(float delta) {
        super.act(delta);
        PositionInfo p = component.getInfo(AllElementInfos.POSITION);
        if (p != null) {
            setPosition((float) p.getX() - WORLD_SIZE / 2f, (float) p.getY() - WORLD_SIZE / 2f);
        }
        RotationInfo r = component.getInfo(AllElementInfos.ROTATION);
        if (r != null) {
            setRotation((float) Math.toDegrees(r.getAngle()));
        }
    }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        Texture tex = textures.getById(component.getRegistryTypeId());
        Color c = getColor();
        batch.setColor(c.r, c.g, c.b, c.a * parentAlpha);
        batch.draw(tex,
                getX(), getY(),
                getOriginX(), getOriginY(),
                getWidth(), getHeight(),
                1f, 1f,
                getRotation(),
                0, 0,
                tex.getWidth(), tex.getHeight(),
                false, false);
        batch.setColor(1f, 1f, 1f, 1f);
    }
}
