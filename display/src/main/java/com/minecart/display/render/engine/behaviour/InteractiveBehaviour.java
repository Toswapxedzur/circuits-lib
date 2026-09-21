package com.minecart.display.render.engine.behaviour;

/**
 * A behaviour driven by the user dragging a sub-part — the Create wrench/scroll-value analog. It owns both
 * the drag metadata (which channel, its range, linear vs rotary, whether it springs back) AND the electrical
 * meaning it produces (a switch that gates conduction, or a variable resistor). The board view keeps the drag
 * <i>geometry</i> (ray→channel projection, which needs the movable's world binding), but reads the
 * <i>semantics</i> from here, and {@code buildCircuit} reads the electrical state from here — so the old
 * datagen {@code Interaction} descriptor is no longer the source of truth.
 */
public interface InteractiveBehaviour extends PartBehaviour {
    /** The {@code AnimationState} channel this control drives (also identifies its movable sub-part). */
    String channel();
    /** Rest / minimum channel value. */
    float min();
    /** Maximum channel value. */
    float max();
    /** Mid-travel — the switch threshold. */
    default float mid() { return (min() + max()) / 2f; }
    /** True for a rotary control (drag about a pivot); false for a linear slide. */
    boolean pivotDrag();
    /** True if it springs back to {@link #min()} when released (a momentary push-button). */
    default boolean momentary() { return false; }

    /** True if this control gates conduction like a switch (participates in the conductor union when closed). */
    default boolean conductorControl() { return false; }
    /** Whether the part conducts at the given channel value (only meaningful when {@link #conductorControl()}). */
    default boolean conducts(float channel) { return true; }
    /** True if this control sets a variable resistance. */
    default boolean resistorControl() { return false; }
    /** The resistance (Ω) at the given channel value (only meaningful when {@link #resistorControl()}). */
    default double resistanceOhms(float channel) { return 100.0; }
}
