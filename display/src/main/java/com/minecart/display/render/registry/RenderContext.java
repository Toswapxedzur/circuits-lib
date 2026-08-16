package com.minecart.display.render.registry;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.minecart.display.render.Textures;

/**
 * Per-element render state for one layout or draw call. Carries the drawing primitives (actor, batch,
 * tint, alpha), the electrical stats snapshot, and the render clock the parts need.
 */
public final class RenderContext {

    private final Actor actor;
    private final Textures textures;
    private final Batch batch;
    private final float parentAlpha;
    private final Color tint;
    private final ElectricalRenderStats electricalStats;
    private final float timeSeconds;

    public RenderContext(Actor actor, Textures textures, Batch batch, float parentAlpha,
                         Color tint, ElectricalRenderStats electricalStats, float timeSeconds) {
        this.actor = actor;
        this.textures = textures;
        this.batch = batch;
        this.parentAlpha = parentAlpha;
        this.tint = tint;
        this.electricalStats = electricalStats != null ? electricalStats : ElectricalRenderStats.EMPTY;
        this.timeSeconds = timeSeconds;
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

    public ElectricalRenderStats electricalStats() {
        return electricalStats;
    }

    public float timeSeconds() {
        return timeSeconds;
    }
}
