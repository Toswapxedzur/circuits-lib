package com.minecart.display.render.engine;

import com.badlogic.gdx.graphics.Color;

/**
 * Per-instance state attached to a placed {@link ComponentInstance} — the analogue of a Minecraft <b>block
 * entity</b>: data that varies per placement, beyond the shared {@link ComponentModel}. Right now it holds the
 * component's <b>colour</b>, which tints the part's greyscale texture at render (so many colours reuse one baked
 * texture — e.g. every LED colour from one greyscale bulb). Extend with more per-instance fields (on/off,
 * brightness, …) as parts need them.
 */
final class ComponentEntity {

    /** The tint colour multiplied over the greyscale texels of this component's {@code tint} boxes. */
    Color color = Color.WHITE;

    /** Emission: when {@link #lightRange} &gt; 0 this component is a <b>point light</b> of colour {@link #light}
     *  (e.g. a lit LED). The engine collects these into the shader's light array. */
    Color light = null;
    float lightRange = 0f;
    float lightYOffset = 9f; // local height of the emitter above the base (the bulb sits ~y9)

    ComponentEntity() {}

    ComponentEntity(Color color) {
        this.color = color;
    }

    /** Fluent: make this component a point light of {@code colour} with {@code range} world units. */
    ComponentEntity emit(Color colour, float range) {
        this.light = colour;
        this.lightRange = range;
        return this;
    }

    boolean emits() {
        return light != null && lightRange > 0f;
    }
}
