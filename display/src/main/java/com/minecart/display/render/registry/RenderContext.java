package com.minecart.display.render.registry;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.minecart.display.render.Textures;
import com.minecart.display.render.WorldStage;

/**
 * Per-element render state for one layout or draw call. Interaction flags are supplied by
 * {@link WorldStage}; renderers can decide how to visualize them without querying stage globals.
 */
public final class RenderContext {

    private final WorldStage stage;
    private final Actor actor;
    private final Textures textures;
    private final Batch batch;
    private final float parentAlpha;
    private final Color tint;
    private final boolean hovered;
    private final boolean dragged;
    private final boolean trashHovered;
    private final boolean editing;
    private final boolean combineTarget;
    private final boolean combineValid;

    public RenderContext(WorldStage stage, Actor actor, Textures textures, Batch batch, float parentAlpha,
                         Color tint, boolean hovered, boolean dragged, boolean trashHovered,
                         boolean editing, boolean combineTarget, boolean combineValid) {
        this.stage = stage;
        this.actor = actor;
        this.textures = textures;
        this.batch = batch;
        this.parentAlpha = parentAlpha;
        this.tint = tint;
        this.hovered = hovered;
        this.dragged = dragged;
        this.trashHovered = trashHovered;
        this.editing = editing;
        this.combineTarget = combineTarget;
        this.combineValid = combineValid;
    }

    public WorldStage stage() {
        return stage;
    }

    public Actor actor() {
        return actor;
    }

    public Textures textures() {
        return textures;
    }

    public Batch batch() {
        return batch;
    }

    public float parentAlpha() {
        return parentAlpha;
    }

    public Color tint() {
        return tint;
    }

    public boolean hovered() {
        return hovered;
    }

    public boolean dragged() {
        return dragged;
    }

    public boolean trashHovered() {
        return trashHovered;
    }

    public boolean editing() {
        return editing;
    }

    public boolean combineTarget() {
        return combineTarget;
    }

    public boolean combineValid() {
        return combineValid;
    }
}
