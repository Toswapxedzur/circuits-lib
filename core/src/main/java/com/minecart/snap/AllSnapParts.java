package com.minecart.snap;

import com.minecart.registry.AllComponents;
import com.minecart.variant.Informations;

/**
 * Built-in snap parts shipped with {@code :core}. Each one wires an existing core element between the two
 * post nodes resolved from the board, so the derived circuit is solved by the same engine 2D mode uses.
 *
 * <p>Touch this class once at startup via {@link #init()} to guarantee registration before a board is
 * derived — same rationale as {@link AllComponents#init()} (class literals don't trigger {@code <clinit>}).
 */
public final class AllSnapParts {

    /** Default electrical values for parts placed without an explicit {@link SnapPlacement#value()}. */
    public static final double DEFAULT_RESISTANCE = 10.0;
    public static final double DEFAULT_VOLTAGE = 5.0;
    private static final double BATTERY_INTERNAL_RESISTANCE = 1e-9;

    /**
     * Ideal connector: merges its two posts into one electrical node rather than adding a device edge, so
     * a run of snap wires is a single node with no spurious resistance. Marked {@code connector = true}.
     */
    public static final SnapPartType SNAP_WIRE = SnapPartRegistry.register(
            "snap_wire", SnapPartType.DEFAULT_HEIGHT, true,
            (placement, world, posts) -> posts.union(placement.postA(), placement.postB()));

    /** Resistor between two posts; ohms come from the placement value or {@link #DEFAULT_RESISTANCE}. */
    public static final SnapPartType SNAP_RESISTOR = SnapPartRegistry.register(
            "snap_resistor", SnapPartType.DEFAULT_HEIGHT, false,
            (placement, world, posts) -> world.connect(
                    AllComponents.RESISTOR, posts.at(placement.postA()), posts.at(placement.postB()),
                    new Informations.ResistorInfo(placement.valueOr(DEFAULT_RESISTANCE))));

    /**
     * Battery from post A (−) to post B (+); volts come from the placement value or {@link #DEFAULT_VOLTAGE}.
     * A near-zero internal resistance keeps it an almost-ideal source while remaining solvable.
     */
    public static final SnapPartType SNAP_BATTERY = SnapPartRegistry.register(
            "snap_battery", SnapPartType.DEFAULT_HEIGHT, false,
            (placement, world, posts) -> world.connect(
                    AllComponents.BATTERY, posts.at(placement.postA()), posts.at(placement.postB()),
                    new Informations.BatteryInfo(placement.valueOr(DEFAULT_VOLTAGE), BATTERY_INTERNAL_RESISTANCE)));

    private AllSnapParts() {}

    /** Empty; calling it forces this class's {@code <clinit>} so the parts above register. */
    public static void init() {
        // Intentionally empty. See javadoc.
    }
}
