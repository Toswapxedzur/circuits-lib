package com.minecart.display.render;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.minecart.logic.CircuitNode;
import com.minecart.registry.AllElementInfos;
import com.minecart.variant.info.PositionInfo;

/**
 * Renders one {@link CircuitNode} as a small textured square at its {@link PositionInfo}. Reads the live
 * info every frame so server updates show up without bookkeeping; the actor's position field is also kept
 * in sync so Scene2D hit-testing and child layout work.
 */
public class NodeActor extends Actor {

    public static final float WORLD_SIZE = 0.6f;

    private final CircuitNode node;
    private final Textures textures;

    public NodeActor(CircuitNode node, Textures textures) {
        this.node = node;
        this.textures = textures;
        setSize(WORLD_SIZE, WORLD_SIZE);
        setOrigin(WORLD_SIZE / 2f, WORLD_SIZE / 2f);
    }

    public CircuitNode getNode() {
        return node;
    }

    @Override
    public void act(float delta) {
        super.act(delta);
        PositionInfo p = node.getInfo(AllElementInfos.POSITION);
        if (p != null) {
            setPosition((float) p.getX() - WORLD_SIZE / 2f, (float) p.getY() - WORLD_SIZE / 2f);
        }
    }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        Texture tex = textures.getById(node.getRegistryTypeId());
        Color c = getColor();
        batch.setColor(c.r, c.g, c.b, c.a * parentAlpha);
        batch.draw(tex, getX(), getY(), WORLD_SIZE, WORLD_SIZE);
        batch.setColor(1f, 1f, 1f, 1f);
    }
}
