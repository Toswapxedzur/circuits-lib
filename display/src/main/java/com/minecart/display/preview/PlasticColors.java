package com.minecart.display.preview;

import com.badlogic.gdx.graphics.Color;

/**
 * The chosen plastic-body colour set for the snap-part series, picked from the colour matrix: one colour per
 * hue, mostly "standard" saturation/value with yellow, blue and violet at "vivid". Each is stored as HSV (the
 * axis the palette was chosen on) and expanded to a 7-shade body ramp via {@link PreviewTextures#ramp}.
 * This is the source of truth for part body colours.
 */
final class PlasticColors {

    private PlasticColors() {}

    record Plastic(String name, float h, float s, float v) {}

    static final Plastic[] SET = {
            new Plastic("red", 0f, 0.92f, 0.80f),
            new Plastic("orange", 30f, 0.92f, 0.80f),
            new Plastic("yellow", 45f, 1.00f, 0.93f),   // vivid
            new Plastic("lime", 85f, 0.92f, 0.80f),
            new Plastic("teal", 160f, 0.92f, 0.80f),
            new Plastic("cyan", 185f, 0.92f, 0.80f),
            new Plastic("azure", 210f, 0.92f, 0.80f),
            new Plastic("blue", 235f, 0.85f, 0.93f),     // vivid
            new Plastic("violet", 265f, 0.85f, 0.93f),   // vivid
            new Plastic("purple", 295f, 0.92f, 0.80f),
            new Plastic("pink", 330f, 0.92f, 0.80f),
    };

    /** The base (index-3) body colour of a plastic. */
    static Color base(Plastic p) {
        Color c = new Color();
        c.fromHsv(p.h(), p.s(), p.v());
        c.a = 1f;
        return c;
    }

    /** The full 7-shade body ramp for a plastic. */
    static Color[] palette(Plastic p) {
        return PreviewTextures.ramp(base(p));
    }
}
