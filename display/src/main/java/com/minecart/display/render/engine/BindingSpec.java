package com.minecart.display.render.engine;

/**
 * A <b>serialisable</b> description of a movable part's {@link MovableBinding} — the data a datagen model JSON
 * stores, from which the runtime rebuilds the (non-serialisable, lambda) binding. Keeping the binding as data
 * here is what lets a component model round-trip through JSON. Only the "translate" kind is used so far.
 *
 * @param type    binding kind — {@code "translate"} (extend with {@code "rotate"} when a rotating part exists)
 * @param channel the {@link AnimationState} channel that drives it
 * @param axis    the motion axis {@code [x,y,z]} (units per channel value, for translate)
 */
record BindingSpec(String type, String channel, float[] axis) {

    static BindingSpec translate(String channel, float ax, float ay, float az) {
        return new BindingSpec("translate", channel, new float[]{ax, ay, az});
    }

    /** Rebuilds the runtime binding lambda from this data. */
    MovableBinding toBinding() {
        return switch (type) {
            case "translate" -> MovableBinding.translate(channel, axis[0], axis[1], axis[2]);
            default -> throw new IllegalArgumentException("Unknown binding type: " + type);
        };
    }
}
