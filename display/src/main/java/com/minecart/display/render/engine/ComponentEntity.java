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

    ComponentEntity() {}

    ComponentEntity(Color color) {
        this.color = color;
    }
}
