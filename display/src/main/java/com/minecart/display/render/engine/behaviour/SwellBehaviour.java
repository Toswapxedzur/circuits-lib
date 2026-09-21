package com.minecart.display.render.engine.behaviour;

/**
 * Reactive: swells a part by its stored charge — a capacitor visibly grows as it charges and relaxes as it
 * discharges. LEVEL mode: it eases a scale channel toward {@code gain·ln(1 + |Q|/qRef)} (a soft, log-shaped
 * growth per the owner's "≈ 1 + log(Q)"), clamped to {@code maxExtra}. The channel feeds a
 * {@code BindingSpec.scale} binding, where the rendered scale is {@code 1 + channel} — so a channel of 0 (no
 * charge) is identity. Stateless; the easing is applied by the per-frame anim update.
 */
public final class SwellBehaviour implements ReactiveBehaviour {
    private final String channel;
    private final float gain;
    private final float qRef;
    private final float maxExtra;

    public SwellBehaviour(String channel, float gain, float qRef, float maxExtra) {
        this.channel = channel;
        this.gain = gain;
        this.qRef = qRef;
        this.maxExtra = maxExtra;
    }

    @Override
    public void react(BehaviourContext ctx) {
        float extra = (float) (gain * Math.log1p(Math.abs(ctx.charge()) / qRef));
        extra = Math.max(0f, Math.min(maxExtra, extra));
        ctx.targetChannel(channel, extra); // eased toward target by the per-frame anim.update
    }
}
