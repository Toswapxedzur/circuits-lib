package com.minecart.display.render.engine.behaviour;

/**
 * Reactive: the part emits point light proportional to its current, so a live circuit glows in-world. A
 * light {@code source} (LED / lamp) is brighter and wider; otherwise it is a warm "heat" glow (a resistor
 * heating up). Colour + source flag are the only parameters, so the three real glows are just three shared
 * instances ({@link #HEAT}/{@link #LED}/{@link #LAMP}). Stateless.
 */
public final class GlowBehaviour implements ReactiveBehaviour {
    /** Below this current (amps) the part does not glow. */
    private static final float ON = 1e-4f;

    private final float r, g, b;
    private final boolean source;

    public GlowBehaviour(float r, float g, float b, boolean source) {
        this.r = r; this.g = g; this.b = b; this.source = source;
    }

    /** Warm-orange resistor/heat glow (dimmer, narrower). */
    public static final GlowBehaviour HEAT = new GlowBehaviour(1f, 0.55f, 0.2f, false);
    /** Red LED (bright, wide light source). */
    public static final GlowBehaviour LED = new GlowBehaviour(1f, 0.15f, 0.1f, true);
    /** Warm-white lamp (bright, wide light source). */
    public static final GlowBehaviour LAMP = new GlowBehaviour(1f, 0.92f, 0.7f, true);

    @Override
    public void react(BehaviourContext ctx) {
        float cur = ctx.current();
        if (cur > ON) {
            float bright = source ? Math.min(1f, 0.9f + cur * 4f) : Math.min(1f, 0.5f + cur * 8f);
            float range = source ? Math.min(110f, 60f + cur * 800f) : Math.min(90f, 30f + cur * 600f);
            ctx.emit(r * bright, g * bright, b * bright, range);
        } else {
            ctx.clearEmission();
        }
    }
}
