package com.minecart.variant.info;

import com.minecart.serialization.tag.CompoundTag;
import com.minecart.variant.ElementInfo;

/**
 * Continuous 2D rotation in radians, attached to a {@link com.minecart.logic.CircuitComponent} so its sprite
 * can be drawn at the right angle and so {@link com.minecart.registry.ComponentAnchorRegistry} can rotate
 * its anchor offsets relative to the component centre.
 * <p>
 * Free nodes and edges don't carry a {@code RotationInfo} (their visual is symmetric or derived from endpoints).
 */
public class RotationInfo implements ElementInfo {

    private static final String TAG_ANGLE = "angle";

    private double angle;

    public RotationInfo() {
        this(0.0);
    }

    public RotationInfo(double angle) {
        this.angle = angle;
    }

    public double getAngle() {
        return angle;
    }

    public void setAngle(double angle) {
        this.angle = angle;
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putDouble(TAG_ANGLE, angle);
    }

    @Override
    public void load(CompoundTag tag) {
        this.angle = tag.getDouble(TAG_ANGLE);
    }
}
