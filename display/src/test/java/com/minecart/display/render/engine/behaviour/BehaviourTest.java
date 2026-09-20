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
    void registryComposesPerPart() {
        assertEquals(2, ComponentBehaviours.reactive("motor").size());   // Spin + HeatGlow
        assertEquals(1, ComponentBehaviours.reactive("led").size());     // LedGlow
        assertEquals(1, ComponentBehaviours.reactive("resistor").size());// HeatGlow
        assertTrue(ComponentBehaviours.reactive("wire_2").isEmpty());    // conductor → none
        assertTrue(ComponentBehaviours.reactive("ic").isEmpty());        // no device → none
    }
}
