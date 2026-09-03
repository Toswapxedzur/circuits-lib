package com.minecart.display.render.engine;

import java.util.HashMap;
import java.util.Map;

/**
 * The per-component animation state (Create's kinetic driver): a small bag of named float channels, each
 * easing from its current value toward a target at a set rate. User actions call {@link #target} (e.g. flip a
 * switch → {@code target("slide", 3)}); {@link #update} eases each frame; {@link MovableBinding}s read the
 * eased {@link #value} to drive a movable part's transform. No allocation per frame.
 */
final class AnimationState {

    // channel -> [current, target, ratePerSecond]
    private final Map<String, float[]> channels = new HashMap<>();

    /** Declares a channel with an initial value, target, and easing rate (units/second). */
    AnimationState channel(String name, float initial, float target, float ratePerSec) {
        channels.put(name, new float[]{initial, target, ratePerSec});
        return this;
    }

    /** Sets a channel's target; {@link #update} eases toward it. */
    void target(String name, float target) {
        float[] c = channels.get(name);
        if (c != null) {
            c[1] = target;
        }
    }

    /** Sets a channel's value IMMEDIATELY (current == target), no easing — for direct user drag. */
    void set(String name, float value) {
        float[] c = channels.get(name);
        if (c != null) {
            c[0] = value;
            c[1] = value;
        }
    }

    /** The channel's current (eased) value; 0 if the channel doesn't exist. */
    float value(String name) {
        float[] c = channels.get(name);
        return c == null ? 0f : c[0];
    }

    /** Eases every channel toward its target by rate*dt. */
    void update(float dt) {
        for (float[] c : channels.values()) {
            float cur = c[0], tgt = c[1], step = c[2] * dt;
            float d = tgt - cur;
            c[0] = Math.abs(d) <= step ? tgt : cur + Math.signum(d) * step;
        }
    }
}
