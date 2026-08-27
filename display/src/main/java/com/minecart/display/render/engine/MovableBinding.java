package com.minecart.display.render.engine;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;

/**
 * Maps {@link AnimationState} channels to a movable part's local motion transform — the hook that makes an
 * instance "move on user action". A part's world matrix is {@code componentWorld · localPlacement · motion},
 * where {@code motion} comes from here (identity for a static part). Implementations must write into {@code out}
 * and return it (no per-frame allocation).
 */
interface MovableBinding {

    /** Sets {@code out} to this binding's motion transform for the current state, and returns it. */
    Matrix4 motion(AnimationState state, Matrix4 out);

    /** Slides along {@code (ax,ay,az)} by the channel's value (units). */
    static MovableBinding translate(String channel, float ax, float ay, float az) {
        return (state, out) -> {
            float v = state.value(channel);
            return out.setToTranslation(ax * v, ay * v, az * v);
        };
    }

    /** Rotates about {@code pivot} around {@code axis} by {@code degPerUnit} × the channel's value. */
    static MovableBinding rotateAbout(String channel, Vector3 pivot, Vector3 axis, float degPerUnit) {
        return (state, out) -> {
            float deg = state.value(channel) * degPerUnit;
            out.idt().translate(pivot).rotate(axis, deg).translate(-pivot.x, -pivot.y, -pivot.z);
            return out;
        };
    }
}
