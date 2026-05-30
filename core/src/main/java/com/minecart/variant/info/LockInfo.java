package com.minecart.variant.info;

import com.minecart.serialization.tag.CompoundTag;
import com.minecart.variant.ElementInfo;

/**
 * Player-set ("strict") lock state attached to a {@link com.minecart.logic.CircuitElement}. Stores the
 * authored {@link LockMode}, the rotation pivot it should rotate around when {@code mode ==
 * PIVOTED}, and a {@code mutableByPlayer} flag — when {@code false} the lock can't be edited
 * via the panel / gestures (intended for elements whose lock is part of their structural definition).
 *
 * <h2>Strict vs. soft</h2>
 * This info carries ONLY the strict (user-authored) state. The soft state is computed at call time
 * by walking constituent nodes (a component's internal port nodes, an edge's endpoints) and reading
 * their {@link PositionInfo#isFixed()}. The effective lock state the rest of the system enforces is
 * {@link LockMode#and(LockMode, LockMode)} of the strict and soft states, with the rotation-pivot
 * reconciliation note described on {@link LockMode#and}.
 *
 * <h2>Pivot semantics</h2>
 * <ul>
 *   <li>When {@link #mode} is {@link LockMode#PIVOTED}, the pivot is the world-space point any
 *       rotation must spin around. The panel exposes editable {@code pivotX} / {@code pivotY}, and
 *       the long-right-press gesture (Phase 3c) updates it to the cursor's world position.</li>
 *   <li>For other modes the pivot is still stored (so toggling to PIVOTED later restores the
 *       last-used pivot rather than snapping to 0,0), but isn't enforced by drag / cascade code.</li>
 *   <li>If no pivot has ever been authored the field defaults to the element's anchor (component
 *       centre or edge midpoint) — set by the placement handler at creation time. We don't compute
 *       a default in the LockInfo itself because the info is anchor-agnostic.</li>
 * </ul>
 */
public class LockInfo implements ElementInfo {

    private static final String TAG_MODE = "mode";
    private static final String TAG_PIVOT_X = "pivotX";
    private static final String TAG_PIVOT_Y = "pivotY";
    private static final String TAG_PIVOT_SET = "pivotSet";
    private static final String TAG_MUTABLE = "mutableByPlayer";

    private LockMode mode;
    private double pivotX;
    private double pivotY;
    /**
     * Whether {@link #pivotX} / {@link #pivotY} carry an authored value (vs. the {@code 0.0} default).
     * Needed because (0,0) is a valid world position; without this flag the renderer / cascade code
     * couldn't tell "pivot at world origin" from "no pivot ever set" and would always snap to origin.
     */
    private boolean pivotSet;
    private boolean mutableByPlayer;

    public LockInfo() {
        this.mode = LockMode.FREE;
        this.pivotX = 0.0;
        this.pivotY = 0.0;
        this.pivotSet = false;
        this.mutableByPlayer = true;
    }

    public LockInfo(LockMode mode, double pivotX, double pivotY, boolean mutableByPlayer) {
        this.mode = mode != null ? mode : LockMode.FREE;
        this.pivotX = pivotX;
        this.pivotY = pivotY;
        this.pivotSet = true;
        this.mutableByPlayer = mutableByPlayer;
    }

    public LockMode getMode() {
        return mode;
    }

    /**
     * Sets the lock mode. No-op (returns {@code false}) when {@link #isMutableByPlayer()} is
     * {@code false} — mirrors {@link PositionInfo#setFixed(boolean)}'s strict-lock guard so any
     * panel / gesture path that tries to flip a permanently-set lock fails silently.
     */
    public boolean setMode(LockMode mode) {
        if (!mutableByPlayer) {
            return false;
        }
        if (mode == null || this.mode == mode) {
            return false;
        }
        this.mode = mode;
        return true;
    }

    public double getPivotX() {
        return pivotX;
    }

    public double getPivotY() {
        return pivotY;
    }

    public boolean isPivotSet() {
        return pivotSet;
    }

    /**
     * Stores a pivot for rotation gestures / panel edits. Caller is responsible for guarding on
     * {@link #isMutableByPlayer()} — pivot edits aren't necessarily mode changes, but it's the same
     * UI surface so the same guard usually applies.
     */
    public void setPivot(double x, double y) {
        this.pivotX = x;
        this.pivotY = y;
        this.pivotSet = true;
    }

    public boolean isMutableByPlayer() {
        return mutableByPlayer;
    }

    public void setMutableByPlayer(boolean mutableByPlayer) {
        this.mutableByPlayer = mutableByPlayer;
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putString(TAG_MODE, mode.name());
        tag.putDouble(TAG_PIVOT_X, pivotX);
        tag.putDouble(TAG_PIVOT_Y, pivotY);
        tag.putBoolean(TAG_PIVOT_SET, pivotSet);
        tag.putBoolean(TAG_MUTABLE, mutableByPlayer);
    }

    @Override
    public void load(CompoundTag tag) {
        // Legacy saves predate LockInfo entirely; missing keys fall back to constructor defaults
        // (FREE, no pivot, mutable). CompoundTag has no contains() probe, so null-get is the test.
        String modeName = tag.getString(TAG_MODE);
        LockMode parsed = LockMode.FREE;
        if (modeName != null && !modeName.isEmpty()) {
            parsed = parseMode(modeName);
        }
        this.mode = parsed;
        this.pivotX = tag.getDouble(TAG_PIVOT_X);
        this.pivotY = tag.getDouble(TAG_PIVOT_Y);
        this.pivotSet = tag.get(TAG_PIVOT_SET) != null && tag.getBoolean(TAG_PIVOT_SET);
        this.mutableByPlayer = tag.get(TAG_MUTABLE) == null || tag.getBoolean(TAG_MUTABLE);
    }

    private static LockMode parseMode(String modeName) {
        return switch (modeName) {
            // Compatibility with saves written before POSITION_FREE/ROTATION_FREE were renamed.
            case "POSITION_FREE" -> LockMode.ORIENTED;
            case "ROTATION_FREE" -> LockMode.PIVOTED;
            default -> {
                try {
                    yield LockMode.valueOf(modeName);
                } catch (IllegalArgumentException ignored) {
                    // Unknown / corrupt mode string: fall back to FREE rather than throwing, matching
                    // the project's overall "forgiving load, strict apply" persistence policy.
                    yield LockMode.FREE;
                }
            }
        };
    }
}
