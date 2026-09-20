package com.minecart.display.render.engine.behaviour;

/**
 * Reactive: advances a rotation channel proportional to the part's current — a motor blade turns faster the
 * more current flows and freezes when the loop opens. RATE mode: the channel itself is the accumulator,
 * wrapped to [0,1) where 1 = a full 360° turn (the {@code BindingSpec.rotate} {@code degPerUnit} does the
 * scaling). Stateless.
 */
public final class SpinBehaviour implements ReactiveBehaviour {
    private final String channel;
    private final float turnsPerSecondPerAmp;

    public SpinBehaviour(String channel, float turnsPerSecondPerAmp) {
        this.channel = channel;
        this.turnsPerSecondPerAmp = turnsPerSecondPerAmp;
    }

    @Override
    public void react(BehaviourContext ctx) {
        float v = ctx.channel(channel) + ctx.current() * turnsPerSecondPerAmp * ctx.dt();
        v -= (float) Math.floor(v);
        ctx.setChannel(channel, v);
    }
}
