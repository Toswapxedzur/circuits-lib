package com.minecart.display.render.engine.behaviour;

/**
 * Interactive: a momentary push-button. Held down (channel {@code "press"}, 0..1) it closes the circuit past
 * mid-travel; on release it springs back to 0 (open) — {@link #momentary()} is true, so the board view eases
 * it back and re-solves as it crosses mid.
 */
public final class MomentaryButtonControl implements InteractiveBehaviour {
    @Override public String channel() { return "press"; }
    @Override public float min() { return 0f; }
    @Override public float max() { return 1f; }
    @Override public boolean pivotDrag() { return false; }
    @Override public boolean momentary() { return true; }
    @Override public boolean conductorControl() { return true; }
    @Override public boolean conducts(float channel) { return channel > mid(); }
}
