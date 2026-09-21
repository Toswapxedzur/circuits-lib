package com.minecart.display.render.engine.behaviour;

/**
 * Interactive: a slide switch. Dragging its knob along the slide axis (channel {@code "slide"}, −2..+2) opens
 * or closes the circuit — closed past mid-travel. Latching (stays where dragged).
 */
public final class SlideSwitchControl implements InteractiveBehaviour {
    @Override public String channel() { return "slide"; }
    @Override public float min() { return -2f; }
    @Override public float max() { return 2f; }
    @Override public boolean pivotDrag() { return false; }
    @Override public boolean conductorControl() { return true; }
    @Override public boolean conducts(float channel) { return channel > mid(); }
}
