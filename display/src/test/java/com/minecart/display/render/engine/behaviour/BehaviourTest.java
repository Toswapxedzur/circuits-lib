package com.minecart.display.render.engine.behaviour;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit-tests the reactive behaviour classes in isolation (they are pure Java — no GL / gdx). Confirms
 * SpinBehaviour accumulates + wraps, GlowBehaviour emits vs clears on the current threshold, and the
 * ComponentBehaviours registry composes the right behaviours per part.
 */
class BehaviourTest {

    /** A tiny in-memory context: channels in a map, emission captured in fields. */
    private static final class Fake implements BehaviourContext.Channels, BehaviourContext.Emission {
        final Map<String, Float> ch = new HashMap<>();
        boolean emitting; float range;
        public float value(String n) { return ch.getOrDefault(n, 0f); }
        public void set(String n, float v) { ch.put(n, v); }
        public void target(String n, float v) { ch.put(n, v); }
        public void emit(float r, float g, float b, float rng) { emitting = true; range = rng; }
        public void clear() { emitting = false; range = 0f; }
        BehaviourContext ctx(float dt, float cur, float volt, float q) {
            return new BehaviourContext(dt, cur, volt, q, this, this);
        }
    }

    @Test
    void spinAccumulatesWithCurrentAndWraps() {
        Fake f = new Fake();
        SpinBehaviour spin = new SpinBehaviour("spin", 90f); // turns/sec per amp
        // 0.1 A for 0.05 s at gain 90 → +0.45 turns.
        spin.react(f.ctx(0.05f, 0.1f, 0f, 0f));
        assertEquals(0.45f, f.value("spin"), 1e-4);
        // Another +0.45 → 0.90.
        spin.react(f.ctx(0.05f, 0.1f, 0f, 0f));
        assertEquals(0.90f, f.value("spin"), 1e-4);
        // +0.45 → 1.35 wraps to 0.35 (channel stays in [0,1)).
        spin.react(f.ctx(0.05f, 0.1f, 0f, 0f));
        assertEquals(0.35f, f.value("spin"), 1e-4);
        // No current → no advance (blade freezes).
        spin.react(f.ctx(0.05f, 0f, 0f, 0f));
        assertEquals(0.35f, f.value("spin"), 1e-4);
    }

    @Test
    void glowEmitsAboveThresholdAndClearsBelow() {
        Fake f = new Fake();
        GlowBehaviour.HEAT.react(f.ctx(0.05f, 0.05f, 0f, 0f));
        assertTrue(f.emitting, "should glow while current flows");
        assertTrue(f.range > 0f);
        GlowBehaviour.HEAT.react(f.ctx(0.05f, 0f, 0f, 0f));
        assertFalse(f.emitting, "should stop glowing when current stops");
    }

    @Test
    void swellGrowsWithChargeAndClamps() {
        Fake f = new Fake();
        SwellBehaviour swell = new SwellBehaviour("swell", 0.15f, 1e-3f, 0.4f);
        // No charge → identity (channel 0 → scale 1).
        swell.react(f.ctx(0.05f, 0f, 0f, 0f));
        assertEquals(0f, f.value("swell"), 1e-6);
        // Some charge → positive, log-shaped growth: 0.15*ln(1 + 5e-3/1e-3) = 0.15*ln(6) ≈ 0.2688.
        swell.react(f.ctx(0.05f, 0f, 0f, 5e-3f));
        assertEquals(0.15f * (float) Math.log(6.0), f.value("swell"), 1e-4);
        // Huge charge clamps to maxExtra.
        swell.react(f.ctx(0.05f, 0f, 0f, 100f));
        assertEquals(0.4f, f.value("swell"), 1e-6);
    }

    @Test
    void interactiveControlsExposeCorrectSemantics() {
        InteractiveBehaviour sw = InteractiveBehaviours.of("switch");
        assertTrue(sw.conductorControl());
        assertFalse(sw.conducts(-2f), "open below mid");   // range -2..2, mid 0
        assertTrue(sw.conducts(2f), "closed above mid");
        assertFalse(sw.momentary());
        assertFalse(sw.pivotDrag());

        InteractiveBehaviour btn = InteractiveBehaviours.of("press");
        assertTrue(btn.momentary(), "push-button springs back");
        assertFalse(btn.conducts(0f), "released = open");   // range 0..1, mid 0.5
        assertTrue(btn.conducts(1f), "held = closed");

        InteractiveBehaviour bar = InteractiveBehaviours.of("varres_bar");
        assertTrue(bar.resistorControl());
        assertEquals(10.0, bar.resistanceOhms(0f), 1e-6);     // min → 10Ω
        assertEquals(1000.0, bar.resistanceOhms(2f), 1e-6);   // max (range 0..2) → 1000Ω
        assertEquals(505.0, bar.resistanceOhms(1f), 1e-6);    // mid → 505Ω

        InteractiveBehaviour dial = InteractiveBehaviours.of("varres_clock");
        assertTrue(dial.pivotDrag(), "dial is rotary");
        assertEquals(1000.0, dial.resistanceOhms(1f), 1e-6);  // range 0..1 → max at 1

        org.junit.jupiter.api.Assertions.assertNull(InteractiveBehaviours.of("wire_2")); // conductor, no control
    }

    @Test
    void registryComposesPerPart() {
        assertEquals(2, ComponentBehaviours.reactive("motor").size());   // Spin + HeatGlow
        assertEquals(1, ComponentBehaviours.reactive("led").size());     // LedGlow
        assertEquals(1, ComponentBehaviours.reactive("resistor").size());// HeatGlow
        assertTrue(ComponentBehaviours.reactive("wire_2").isEmpty());    // conductor → none
        assertTrue(ComponentBehaviours.reactive("ic").isEmpty());        // no device → none
    }
}
