package com.minecart.display.render.registry;

import com.badlogic.gdx.graphics.Color;

/** Shared white-to-deep-blue current color ramp. */
public final class CurrentRenderColors {

    private static final Color DEEP_BLUE = new Color(0.02f, 0.08f, 0.85f, 1f);

    private CurrentRenderColors() {}

    public static Color colorFor(float intensity, float alpha) {
        float t = Math.max(0f, Math.min(1f, intensity));
        return new Color(
                lerp(1f, DEEP_BLUE.r, t),
                lerp(1f, DEEP_BLUE.g, t),
                lerp(1f, DEEP_BLUE.b, t),
                alpha);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
