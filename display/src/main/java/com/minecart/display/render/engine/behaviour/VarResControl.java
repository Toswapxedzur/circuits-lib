package com.minecart.display.render.engine.behaviour;

/**
 * Interactive: a variable resistor. Dragging its knob (a linear slider or a rotary dial) maps the channel's
 * position across its range to a resistance, 10 Ω … 1000 Ω. Latching.
 */
public final class VarResControl implements InteractiveBehaviour {
    private final String channel;
    private final float min, max;
    private final boolean pivot;

    public VarResControl(String channel, float min, float max, boolean pivot) {
        this.channel = channel;
        this.min = min;
        this.max = max;
        this.pivot = pivot;
    }

    @Override public String channel() { return channel; }
    @Override public float min() { return min; }
    @Override public float max() { return max; }
    @Override public boolean pivotDrag() { return pivot; }
    @Override public boolean resistorControl() { return true; }

    @Override
    public double resistanceOhms(float channel) {
        float frac = max == min ? 0f : (channel - min) / (max - min);
        return 10.0 + Math.max(0f, Math.min(1f, frac)) * 990.0;
    }
}
