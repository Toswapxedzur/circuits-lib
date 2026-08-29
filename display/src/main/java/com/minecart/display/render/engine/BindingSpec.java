package com.minecart.display.render.engine;

import com.badlogic.gdx.math.Vector3;

/**
 * A <b>serialisable</b> description of a movable part's {@link MovableBinding} — the data a datagen model JSON
 * stores, from which the runtime rebuilds the (non-serialisable, lambda) binding. Keeping the binding as data
 * here is what lets a component model round-trip through JSON.
 *
 * @param type       binding kind — {@code "translate"} or {@code "rotate"}
 * @param channel    the {@link AnimationState} channel that drives it
 * @param axis       for translate: the motion axis {@code [x,y,z]} (units per channel value); for rotate: the
 *                   rotation axis {@code [x,y,z]}
 * @param pivot      rotate only: the pivot point {@code [x,y,z]} to spin about (null for translate)
 * @param degPerUnit rotate only: degrees of rotation per unit of the channel value (0 for translate)
 */
record BindingSpec(String type, String channel, float[] axis, float[] pivot, float degPerUnit) {

    static BindingSpec translate(String channel, float ax, float ay, float az) {
        return new BindingSpec("translate", channel, new float[]{ax, ay, az}, null, 0f);
    }

    /** Spins about {@code pivot} around {@code axis} by {@code degPerUnit} × the channel value. */
    static BindingSpec rotate(String channel, float px, float py, float pz, float ax, float ay, float az, float degPerUnit) {
        return new BindingSpec("rotate", channel, new float[]{ax, ay, az}, new float[]{px, py, pz}, degPerUnit);
    }

    /** Rebuilds the runtime binding lambda from this data. */
    MovableBinding toBinding() {
        return switch (type) {
            case "translate" -> MovableBinding.translate(channel, axis[0], axis[1], axis[2]);
            case "rotate" -> MovableBinding.rotateAbout(channel,
                    new Vector3(pivot[0], pivot[1], pivot[2]), new Vector3(axis[0], axis[1], axis[2]), degPerUnit);
            default -> throw new IllegalArgumentException("Unknown binding type: " + type);
        };
    }
}
