package com.minecart.display.snap;

import com.badlogic.gdx.math.Matrix4;
import com.minecart.snap.SnapPlacement;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure mapping from the snap-board domain ({@link SnapPlacement}) to the instanced engine's inputs — an engine
 * <b>model id</b> + a world {@link Matrix4} per placement. It is deliberately engine-free (no
 * {@code render.engine} import): it emits {@code (id, matrix)} pairs that the engine-package adapter later feeds
 * to {@code ModelLoader.model(id)} + {@code ComponentInstance}. Keeping it isolated lets the grid/mapping design
 * be written and unit-tested without touching the concurrently-edited engine package.
 *
 * <h2>Unified grid (owner decision 2026-08-28)</h2>
 * Board posts are {@link #PITCH}=24 world units apart, so a <b>length-1</b> part (two adjacent posts, 24 apart)
 * lines up with a standard engine model whose two studs sit at x=±12. Each stack layer is {@link #LEVEL}=5
 * (base tile 4 + stud 1). A part is placed at the <b>midpoint</b> of its two posts and yawed to its (dCol,dRow)
 * heading (the model runs along +X, studs on ±X; +col→+X, +row→+Z).
 */
public final class SnapModelBridge {

    /** World units between adjacent board posts (== a standard engine model's stud span, ±12). */
    public static final float PITCH = 24f;
    /** World units per stack layer (engine base tile 4 + stud 1 == the board's LEVEL_HEIGHT). */
    public static final float LEVEL = 5f;

    private static final String DEFAULT_RESISTOR = "resistor";

    private SnapModelBridge() {}

    /**
     * A placeable snap COMPONENT: its committed model id, hotbar label, and electrical {@code kind} char used by the
     * physical board's circuit builder — {@code w}=wire/junction (unifies its terminals), {@code s}=switch (a closed
     * conductor for now), {@code r}=resistor, {@code c}=capacitor, {@code d}=diode, {@code l}=LED (diode + light),
     * {@code p}=lamp (resistor + light), {@code b}=battery, {@code .}=place-only (no 2-terminal electrical yet, e.g.
     * a 3-pin transistor or an IC). This CATALOG is the single registration point — the hotbar, the atlas preload,
     * and {@link #kindOf} all derive from it.
     */
    public record Comp(String modelId, String label, char kind) {}

    /** Every component registered for the physical board. Order = hotbar order. */
    public static final List<Comp> CATALOG = List.of(
            new Comp("wire_2", "Wire", 'w'),
            new Comp("tee_blue", "Tee", 'w'),
            new Comp("resistor", "Resistor", 'r'),
            new Comp("varres_bar", "Var.Res", 'r'),
            new Comp("capacitor_small", "Cap S", 'c'),
            new Comp("capacitor_medium", "Cap M", 'c'),
            new Comp("capacitor_big", "Cap L", 'c'),
            new Comp("diode", "Diode", 'd'),
            new Comp("led", "LED", 'l'),
            new Comp("lamp", "Lamp", 'p'),
            new Comp("switch", "Switch", 's'),
            new Comp("press", "Button", 's'),
            new Comp("battery_cell", "Battery", 'b'),
            new Comp("transistor_npn", "NPN", '.'),
            new Comp("transistor_pnp", "PNP", '.'),
            new Comp("ic", "IC", '.'));

    /** The electrical kind of a model id (from the {@link #CATALOG}); '.' if unregistered / place-only. */
    public static char kindOf(String modelId) {
        for (Comp c : CATALOG) {
            if (c.modelId().equals(modelId)) {
                return c.kind();
            }
        }
        return '.';
    }

    /** One thing for the engine to draw: which model, and where. */
    public record Placed(String modelId, Matrix4 world) {}

    /**
     * The engine model id for a placement's part type. Only wire + resistor have engine art today; the battery
     * has no model yet (spec locked, generation pending) so it falls back to a red base tile placeholder.
     */
    public static String modelId(SnapPlacement p) {
        return switch (p.type().id()) {
            case "snap_wire" -> "wire_2";
            case "snap_resistor" -> DEFAULT_RESISTOR;
            case "snap_battery" -> "battery_cell"; // the loose battery cell model
            default -> "base_teal";               // unknown type → a neutral base tile
        };
    }

    /** Every model id {@link #modelId} can emit — so the renderer can pre-load them all (e.g. for the placement
     *  ghost, whose sprites must be in the atlas before any part of that type is placed). */
    public static List<String> allModelIds() {
        List<String> out = new ArrayList<>();
        for (Comp c : CATALOG) out.add(c.modelId());
        out.add("base_teal"); // the ghost-placeholder base tile
        return out;
    }

    /**
     * Placement → world transform: translate to the midpoint of its two posts on its layer, then yaw to its
     * heading. {@code flipped} (electrical polarity) has no geometric effect for the symmetric parts modelled so
     * far and is ignored; revisit when a polarised part (diode/battery) gets real art.
     */
    public static Matrix4 world(SnapPlacement p) {
        float mx = (p.col() + p.dCol() / 2f) * PITCH;
        float mz = (p.row() + p.dRow() / 2f) * PITCH;
        float ty = p.layer() * LEVEL;
        float yawDeg = (float) Math.toDegrees(Math.atan2(p.dRow(), p.dCol())); // EAST(1,0)=0°, NORTH(0,1)=90°
        return new Matrix4().setToTranslation(mx, ty, mz).rotate(0f, 1f, 0f, -yawDeg);
    }

    /** Maps a snapshot of placements to their engine draws. */
    public static List<Placed> parts(Iterable<SnapPlacement> placements) {
        List<Placed> out = new ArrayList<>();
        for (SnapPlacement p : placements) out.add(new Placed(modelId(p), world(p)));
        return out;
    }
}
