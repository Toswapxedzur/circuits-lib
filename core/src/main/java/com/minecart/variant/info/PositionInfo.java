package com.minecart.variant.info;

import com.minecart.serialization.tag.CompoundTag;
import com.minecart.variant.ElementInfo;

/**
 * 2D world-space position attached to a {@link com.minecart.logic.CircuitElement} so the display module
 * can render it and so the server can record where a freshly placed element belongs. Persisted with the
 * circuit so layout is preserved across save/load.
 * <p>
 * Coordinates are abstract world units; the renderer applies its own pixels-per-unit scale.
 *
 * <h2>Fixed positions</h2>
 * The {@link #isFixed()} flag marks a position as not-user-movable: drag handlers (client) and move
 * payload handlers (server) must refuse to mutate {@link #x}/{@link #y} on a fixed position. Used for the
 * internal port / centre nodes of a {@link com.minecart.logic.CircuitComponent} which only move as a unit
 * with their parent. The {@link #canChangeFix()} flag controls whether {@link #setFixed(boolean)} is
 * accepted at all — a hard {@code false} means even the editor can't toggle the lock (e.g. component
 * internals whose anchor offsets are part of the device's electrical model). Plain free nodes default to
 * {@code fixed=false}, {@code canChangeFix=true}.
 */
public class PositionInfo implements ElementInfo {

    private static final String TAG_X = "x";
    private static final String TAG_Y = "y";
    private static final String TAG_FIXED = "fixed";
    private static final String TAG_CAN_CHANGE_FIX = "canChangeFix";

    private double x;
    private double y;
    private boolean fixed;
    private boolean canChangeFix;

    public PositionInfo() {
        this(0.0, 0.0);
    }

    public PositionInfo(double x, double y) {
        this.x = x;
        this.y = y;
        this.fixed = false;
        this.canChangeFix = true;
    }

    public double getX() { return x; }
    public double getY() { return y; }

    public void set(double x, double y) {
        this.x = x;
        this.y = y;
    }

    public void setX(double x) { this.x = x; }
    public void setY(double y) { this.y = y; }

    public boolean isFixed() {
        return fixed;
    }

    /**
     * Sets the fixed state. No-op (returns {@code false}) when {@link #canChangeFix()} is {@code false},
     * so component internals stamped with {@code canChangeFix=false} stay locked even if a stale UI path
     * tries to flip them.
     *
     * @return {@code true} if the state actually changed.
     */
    public boolean setFixed(boolean fixed) {
        if (!canChangeFix) {
            return false;
        }
        if (this.fixed == fixed) {
            return false;
        }
        this.fixed = fixed;
        return true;
    }

    public boolean canChangeFix() {
        return canChangeFix;
    }

    public void setCanChangeFix(boolean canChangeFix) {
        this.canChangeFix = canChangeFix;
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putDouble(TAG_X, x);
        tag.putDouble(TAG_Y, y);
        tag.putBoolean(TAG_FIXED, fixed);
        tag.putBoolean(TAG_CAN_CHANGE_FIX, canChangeFix);
    }

    @Override
    public void load(CompoundTag tag) {
        this.x = tag.getDouble(TAG_X);
        this.y = tag.getDouble(TAG_Y);
        // Older saves predate the lock fields; absent tags fall back to the constructor defaults
        // (movable + togglable) instead of silently locking every legacy element in place. CompoundTag
        // has no explicit "contains" probe, so a null get() means the key wasn't written.
        this.fixed = tag.get(TAG_FIXED) != null && tag.getBoolean(TAG_FIXED);
        this.canChangeFix = tag.get(TAG_CAN_CHANGE_FIX) == null || tag.getBoolean(TAG_CAN_CHANGE_FIX);
    }
}
