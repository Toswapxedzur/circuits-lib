package com.minecart.variant.info;

import com.minecart.serialization.tag.CompoundTag;
import com.minecart.variant.ElementInfo;

/**
 * 2D world-space position attached to a {@link com.minecart.logic.CircuitElement} so the display module
 * can render it and so the server can record where a freshly placed element belongs. Persisted with the
 * circuit so layout is preserved across save/load.
 * <p>
 * Coordinates are abstract world units; the renderer applies its own pixels-per-unit scale.
 */
public class PositionInfo implements ElementInfo {

    private static final String TAG_X = "x";
    private static final String TAG_Y = "y";

    private double x;
    private double y;

    public PositionInfo() {
        this(0.0, 0.0);
    }

    public PositionInfo(double x, double y) {
        this.x = x;
        this.y = y;
    }

    public double getX() { return x; }
    public double getY() { return y; }

    public void set(double x, double y) {
        this.x = x;
        this.y = y;
    }

    public void setX(double x) { this.x = x; }
    public void setY(double y) { this.y = y; }

    @Override
    public void save(CompoundTag tag) {
        tag.putDouble(TAG_X, x);
        tag.putDouble(TAG_Y, y);
    }

    @Override
    public void load(CompoundTag tag) {
        this.x = tag.getDouble(TAG_X);
        this.y = tag.getDouble(TAG_Y);
    }
}
