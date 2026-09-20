package com.minecart.display.render.engine.behaviour;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The registry binding a snap component (by model id) to its composed behaviours — the single place that
 * says "a motor spins and glows, an LED glows red, a resistor heats up". This replaces the per-{@code kind}
 * {@code if}/{@code switch} blocks that used to live in {@code PhysicalBoardView} ({@code updateMotors},
 * {@code updateElectricalGlow}); adding an animated part is one line here plus, if needed, one behaviour
 * class — editing zero switches. (The Create {@code AllBlocks}/behaviour-registration analog.)
 */
public final class ComponentBehaviours {
    private ComponentBehaviours() {}

    private static final Map<String, List<ReactiveBehaviour>> REACTIVE = new HashMap<>();

    private static void reactive(String modelId, ReactiveBehaviour... behaviours) {
        REACTIVE.put(modelId, List.of(behaviours));
    }

    static {
        reactive("motor", new SpinBehaviour("spin", 90f), GlowBehaviour.HEAT);
        reactive("led", GlowBehaviour.LED);
        reactive("lamp", GlowBehaviour.LAMP);
        // Every other current-carrying device just heats up (matches the old default glow branch).
        for (String heat : new String[]{
                "resistor", "varres_bar", "varres_clock", "diode",
                "capacitor_small", "capacitor_medium", "capacitor_big",
                "battery", "battery_cell", "transistor_npn", "transistor_pnp"}) {
            reactive(heat, GlowBehaviour.HEAT);
        }
        // Conductors (wire/tee/switch/press) and the IC have no device edge → no reactive behaviour.
    }

    /** The reactive behaviours for a model id, in application order; empty if the part has none. */
    public static List<ReactiveBehaviour> reactive(String modelId) {
        return REACTIVE.getOrDefault(modelId, List.of());
    }
}
