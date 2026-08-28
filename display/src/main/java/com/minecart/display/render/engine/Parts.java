package com.minecart.display.render.engine;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Vector3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The part + component library, as pure data (no GL). Geometry AND texture recipe match {@code PreviewPart}
 * exactly — each box carries the same {@link PaletteDither.Paint} as the corresponding {@code PreviewPart.box}
 * call. The plastic BODY is recolourable across the whole {@link #PLASTIC_HSV} series (PlasticColors); only its
 * ramp palette changes per hue, the seeds/dither stay the same, and the band/steel/knob are shared. So there is
 * one capacitor + one slide switch per body colour.
 */
final class Parts {

    // Series shading frame (== ModelPreviewApp.SHADING + PreviewPart's HALF_X/Y/Z and stud extents).
    private static final float L = (float) Math.sqrt(0.5 * 0.5 + 0.7 * 0.7 + 0.4 * 0.4);
    private static final float SHADE_R =
            Math.abs(0.5f / L) * 16.5f + Math.abs(0.7f / L) * 3f + Math.abs(0.4f / L) * 4.5f;
    private static final float STUD_R = Math.max(1f,
            Math.abs(0.5f / L) * 1.5f + Math.abs(0.7f / L) * 0.5f + Math.abs(0.4f / L) * 1.5f);

    /** The series shading radius for a body of half-width {@code halfX} — the standard body's 16.5 gives
     *  exactly {@link #SHADE_R}; the wire family passes its own so the gradient spans the longer body. */
    private static float shadeR(float halfX) {
        return Math.abs(0.5f / L) * halfX + Math.abs(0.7f / L) * 3f + Math.abs(0.4f / L) * 4.5f;
    }

    /** The plastic body colour set (PlasticColors.SET), as HSV — one capacitor + switch is built per row. */
    static final float[][] PLASTIC_HSV = {
            {0f, 0.92f, 0.80f},   // red
            {30f, 0.92f, 0.80f},  // orange
            {45f, 1.00f, 0.93f},  // yellow (vivid)
            {85f, 0.92f, 0.80f},  // lime
            {160f, 0.92f, 0.80f}, // teal
            {185f, 0.92f, 0.80f}, // cyan
            {210f, 0.92f, 0.80f}, // azure
            {235f, 0.85f, 0.93f}, // blue (vivid)
            {265f, 0.85f, 0.93f}, // violet (vivid)
            {295f, 0.92f, 0.80f}, // purple
            {330f, 0.92f, 0.80f}, // pink
    };
    static final String[] PLASTIC_NAME = {
            "red", "orange", "yellow", "lime", "teal", "cyan", "azure", "blue", "violet", "purple", "pink"};

    private static final Color[] BAND = PaletteDither.grays(6, 0.85f, 1.0f);        // white plastic band
    private static final Color[] STEEL = PaletteDither.steelBlue();                 // metal
    private static final Color[] KNOB = PaletteDither.ramp(new Color(0.12f, 0.12f, 0.14f, 1f)); // near-black
    private static final Color[] CAP_BODY = PaletteDither.ramp(new Color(0.07f, 0.07f, 0.08f, 1f)); // black cap body
    private static final Color[] CAP_BASE = PaletteDither.rampHsv(160f, 0.92f, 0.80f); // teal snap base plastic
    private static final Color[] RES_BODY = PaletteDither.ramp(new Color(0.82f, 0.68f, 0.45f, 1f)); // tan resistor body
    private static final Color[] RES_B1 = PaletteDither.ramp(new Color(0.35f, 0.20f, 0.10f, 1f));   // band: brown
    private static final Color[] RES_B2 = PaletteDither.ramp(new Color(0.72f, 0.10f, 0.10f, 1f));   // band: red
    private static final Color[] RES_B3 = PaletteDither.ramp(new Color(0.82f, 0.62f, 0.18f, 1f));   // band: gold
    private static final Color[] BULB_CORE = PaletteDither.grays(6, 0.60f, 1.00f);  // greyscale inner glow (TINTED)
    private static final Color[] BULB_GLASS = PaletteDither.grays(6, 0.70f, 1.00f); // greyscale glass (TINTED + translucent)
    private static final Color TRACE_WHITE = new Color(0.95f, 0.95f, 0.95f, 1f);    // printed trace (default)
    private static final Color TRACE_RED = new Color(0.86f, 0.13f, 0.13f, 1f);      // printed trace (resistor)
    private static final Color BAND_WHITE = new Color(0.97f, 0.97f, 0.96f, 1f);     // band diffuse tint

    private static final float OX = 0.5f, OZ = 0.5f; // slide-switch centre offset, kept from PreviewPart

    /** Capacitor sizes: {body W×W footprint, body height, leg height}. Leg is 1 wide × legH tall × 0 thick. */
    static final float[][] CAP_SIZES = {{7f, 9f, 2f}, {5f, 6f, 3f}, {3f, 4f, 4f}}; // big, medium, small

    /** The wire family's generated size range (grid points spanned). ONE wire component type — its length is a
     *  component STATE, so the range is just this datagen bound: bump WIRE_MAX and re-run datagen for more. */
    static final int WIRE_MIN = 2, WIRE_MAX = 7;

    /** The wire's plastic colour: azure ({@link #PLASTIC_HSV}[{@link #WIRE_COLOR}]), the owner's pick. */
    static final int WIRE_COLOR = 6;

    final PartType slider;                              // slide switch's mover (colour-independent)
    final PartType button;                              // press switch's plunger (colour-independent)
    final ComponentModel[] bases = new ComponentModel[PLASTIC_HSV.length];         // blank base board, one per colour
    final ComponentModel[] capacitorSizes = new ComponentModel[CAP_SIZES.length];  // big/medium/small (teal)
    final ComponentModel[] switches = new ComponentModel[PLASTIC_HSV.length];
    final ComponentModel[] pressSwitches = new ComponentModel[PLASTIC_HSV.length];
    final ComponentModel[] resistors = new ComponentModel[PLASTIC_HSV.length];
    final ComponentModel[] diodes = new ComponentModel[PLASTIC_HSV.length];
    final ComponentModel[] leds = new ComponentModel[PLASTIC_HSV.length];
    final ComponentModel[] wires = new ComponentModel[WIRE_MAX - WIRE_MIN + 1]; // azure, indexed n − WIRE_MIN

    // Paint factories — plastic is per-colour; the rest are shared across colours.
    private static PaletteDither.Paint plastic(long seed, Color[] pal) {
        return plastic(seed, pal, SHADE_R);
    }

    private static PaletteDither.Paint plastic(long seed, Color[] pal, float r) {
        return new PaletteDither.Paint(pal, Color.WHITE, 2, 0.3f, false, seed, 0f, 2f, 0f, r, 1f);
    }

    private static PaletteDither.Paint band(long seed) {
        return band(seed, SHADE_R);
    }

    private static PaletteDither.Paint band(long seed, float r) {
        return new PaletteDither.Paint(BAND, BAND_WHITE, 1, 0.3f, false, seed, 0f, 2f, 0f, r, 1f);
    }

    private static PaletteDither.Paint fence(long seed) {
        return new PaletteDither.Paint(STEEL, Color.WHITE, 1, 1.6f, true, seed, 0f, 2f, 0f, SHADE_R, 1f);
    }

    private static PaletteDither.Paint stud(long seed, float cx, float cy, float cz) {
        return new PaletteDither.Paint(STEEL, Color.WHITE, 1, 1.6f, true, seed, cx, cy, cz, STUD_R, 1f);
    }

    private static PaletteDither.Paint knob(long seed) {
        return new PaletteDither.Paint(KNOB, Color.WHITE, 2, 0.3f, false, seed, 0f, 2f, 0f, SHADE_R, 1f);
    }

    // LED bulb paints — GREYSCALE bases (the colour comes from the component-entity tint), shade-centred on the bulb.
    private static PaletteDither.Paint bulbCore(long seed) {   // solid inner glow (tinted)
        return new PaletteDither.Paint(BULB_CORE, Color.WHITE, 2, 0.3f, false, seed, 0f, 8f, 0f, SHADE_R, 1f);
    }

    private static PaletteDither.Paint bulbGlass(long seed) {  // translucent outer glass (tinted, alpha 0.45)
        return new PaletteDither.Paint(BULB_GLASS, Color.WHITE, 2, 0.3f, false, seed, 0f, 8f, 0f, SHADE_R, 0.45f);
    }

    private static PaletteDither.Paint tube(long seed) {       // metal screw base (NOT tinted)
        return new PaletteDither.Paint(STEEL, Color.WHITE, 1, 1.6f, true, seed, 0f, 5f, 0f, SHADE_R, 1f);
    }

    Parts() {
        slider = new PartType("slider", List.of(
                new PartMesh.Box(0f, 0f, 0f, 2f, 2f, 2f, knob(260L), -0.5f, 5f, 0.5f, null, false, false, PartMesh.WHITE_BITS, null)));
        // Press button: a 3×3 plunger, height 3, centred on the body, resting with its top 3px above the body
        // top (y4 → 7). Pressing (channel "press" 0→1) drops it 2 in Y so its top is 1px above (y5).
        button = new PartType("button", List.of(
                new PartMesh.Box(0f, 0f, 0f, 3f, 3f, 3f, knob(360L), 0f, 5.5f, 0f, null, false, false, PartMesh.WHITE_BITS, null)));
        Color[] teal = PaletteDither.rampHsv(160f, 0.92f, 0.80f);
        for (int s = 0; s < CAP_SIZES.length; s++) { // the 3 sizes, in teal
            capacitorSizes[s] = buildCapacitor(teal, CAP_SIZES[s][0], CAP_SIZES[s][1], CAP_SIZES[s][2], 800L + s * 10L);
        }
        for (int c = 0; c < PLASTIC_HSV.length; c++) {
            Color[] pal = PaletteDither.rampHsv(PLASTIC_HSV[c][0], PLASTIC_HSV[c][1], PLASTIC_HSV[c][2]);
            bases[c] = base("base", pal).build(); // the blank snap base board, in every plastic colour
            switches[c] = buildSwitch(pal);
            pressSwitches[c] = buildPressSwitch(pal);
            resistors[c] = buildResistor(pal);
            diodes[c] = buildDiode(pal);
            leds[c] = buildLed(pal);
        }
        Color[] azure = PaletteDither.rampHsv(
                PLASTIC_HSV[WIRE_COLOR][0], PLASTIC_HSV[WIRE_COLOR][1], PLASTIC_HSV[WIRE_COLOR][2]);
        for (int n = WIRE_MIN; n <= WIRE_MAX; n++) {
            wires[n - WIRE_MIN] = buildWire(n, azure);
        }
    }

    /** The coloured rims + white band shared by every part — the TOP rim's +Y face carries the {@code trace}
     *  decal (printed line between the studs, capacitor symbol, etc.), or none if {@code trace} is null. */
    private ComponentModel.Builder rims(ComponentModel.Builder b, Color[] pal, PartMesh.Trace trace) {
        return b.box(0f, 0.5f, 0f, 33f, 1f, 9f, plastic(101L, pal))         // coloured rim y0..1
                .box(0f, 3.5f, 0f, 33f, 1f, 9f, plastic(202L, pal), trace)  // coloured rim y3..4 (top — trace here)
                .box(0f, 2f, 0f, 33f, 2f, 9f, band(303L));                  // white band y1..3
    }

    /** The standard part base: rims + white band + two end snap studs at ±12, with an optional top-face trace. */
    private ComponentModel.Builder base(String id, Color[] pal, PartMesh.Trace trace) {
        return studs(rims(ComponentModel.of(id), pal, trace));
    }

    private ComponentModel.Builder base(String id, Color[] pal) {
        return base(id, pal, null);
    }

    // Printed-trace decals (flat, baked into the top-face texture). White by default, red for the resistor;
    // the diode's red line grows an arrowhead pointing in its flow direction (+x).
    private static PartMesh.Trace whiteTrace() { return new PartMesh.Trace(TRACE_WHITE, false, false); }
    private static PartMesh.Trace redTrace() { return new PartMesh.Trace(TRACE_RED, false, false); }
    private static PartMesh.Trace capTrace() { return new PartMesh.Trace(TRACE_WHITE, true, false); }
    private static PartMesh.Trace diodeTrace() { return new PartMesh.Trace(TRACE_RED, false, true); }

    /** The wire's printed line: stud-to-stud across the whole family, span = outer stud offset − 1.5. */
    private static PartMesh.Trace wireTrace(float span) {
        return new PartMesh.Trace(TRACE_WHITE, false, false, span);
    }

    /**
     * Wire (conductor link) of size {@code n} — ONE component type whose length is a component STATE; the model
     * is generated per size by this one builder, freely extensible via {@link #WIRE_MAX}. Azure plastic, the
     * standard rim + white band + rim profile on the grid formula width = (n−1)·12 + 9 (3px end buffers). A
     * metal snap stud at EVERY grid point on top — a conductor connects wherever it crosses — but only TWO
     * underside sockets, one at each END (it mounts by its ends). The white trace runs stud-to-stud, passing
     * under the intermediate studs. n=3 reproduces the standard body exactly, so its rim/band/stud/socket
     * sprites all dedupe with the other parts'.
     */
    private ComponentModel buildWire(int n, Color[] pal) {
        float s = (n - 1) * 6f;                 // outer stud offset: studs at −s..s, pitch 12
        float w = (n - 1) * 12f + 9f;
        float r = shadeR(w / 2f);
        ComponentModel.Builder b = ComponentModel.of("wire")
                .box(0f, 0.5f, 0f, w, 1f, 9f, plastic(101L, pal, r))                      // rim y0..1
                .box(0f, 3.5f, 0f, w, 1f, 9f, plastic(202L, pal, r), wireTrace(s - 1.5f)) // top rim + trace
                .box(0f, 2f, 0f, w, 2f, 9f, band(303L, r));                               // white band y1..3
        for (int k = 0; k < n; k++) {           // a stud at EVERY grid point (local shading frame → all dedupe)
            float x = -s + k * 12f;
            b = b.box(x, 4.5f, 0f, 3f, 1f, 3f, stud(404L, x, 4.5f, 0f));
        }
        return socket(socket(b, -s), s).build(); // underside sockets at the ENDS only
    }

    /**
     * The two snap studs, at the part's ENDS (x = ±12), + the matching two underside female <b>sockets</b>.
     * A part <b>occupies 3 stud spaces</b> (its 33-wide body spans grid points −12/0/+12) but carries <b>only
     * these two studs on top</b> — the middle grid point is covered but unstudded, exactly like a multi-cell
     * Snap-Circuits part.
     */
    private ComponentModel.Builder studs(ComponentModel.Builder b) {
        b = b.box(-12f, 4.5f, 0f, 3f, 1f, 3f, stud(404L, -12f, 4.5f, 0f))
                .box(12f, 4.5f, 0f, 3f, 1f, 3f, stud(404L, 12f, 4.5f, 0f));
        return socket(socket(b, -12f), 12f);
    }

    /**
     * The underside female snap socket at one end (x). A 1px-tall <b>steel fence</b> — outer 5×5, 1px walls,
     * inner 3×3 hole — protruding just below the body (y −1..0), with a <b>recessed metal floor</b> (3×3,
     * flush with the body bottom y0) so the interior is metal but the cavity stays hollow: it caps the 3×3 top
     * stud of the part below. Snap-Circuits female snap. Seeds are shared across both ends → the sockets dedupe.
     */
    private ComponentModel.Builder socket(ComponentModel.Builder b, float x) {
        return b.box(x - 2f, -0.5f, 0f, 1f, 1f, 5f, fence(501L)) // fence: left wall  (full depth)
                .box(x + 2f, -0.5f, 0f, 1f, 1f, 5f, fence(501L)) // fence: right wall
                .box(x, -0.5f, -2f, 3f, 1f, 1f, fence(502L))     // fence: front wall (fills the gap)
                .box(x, -0.5f, 2f, 3f, 1f, 1f, fence(502L))      // fence: back wall
                .box(x, 0f, 0f, 3f, 0f, 3f, fence(503L));        // recessed metal floor (down-facing skin at y0)
    }

    /**
     * Resistor: the standard base + a horizontal tan body (11×3×3) laid across it, banded with three colour
     * codes. The body is built as 4 tan segments + 3 colour bands (all 3×3, abutting) so no faces overlap —
     * neighbour culling drops the seams. Body + bands are colour-independent → they dedupe across every base hue.
     */
    private ComponentModel buildResistor(Color[] pal) {
        // Body sits at y4..7; two metal LEAD-plates emerge from each 3×3 end and angle down to the base. Each
        // plate is a TILTED flat quad = the hypotenuse (len 3) of a 2-√5-3 right triangle (rise 2 from base y4
        // to the lead's top y6 on the end face, run √5 outward toward the terminal), 1 wide in Z — laid like a ladder.
        float s5 = (float) Math.sqrt(5.0);
        return base("resistor", pal, redTrace())
                .box(-4.5f, 5.5f, 0f, 2f, 3f, 3f, plastic(901L, RES_BODY)) // tan   x -5.5..-3.5, y4..7
                .box(-3f, 5.5f, 0f, 1f, 3f, 3f, plastic(902L, RES_B1))     // brown x -3.5..-2.5
                .box(-1.5f, 5.5f, 0f, 2f, 3f, 3f, plastic(901L, RES_BODY)) // tan   x -2.5..-0.5
                .box(0f, 5.5f, 0f, 1f, 3f, 3f, plastic(903L, RES_B2))      // red   x -0.5..0.5
                .box(1.5f, 5.5f, 0f, 2f, 3f, 3f, plastic(901L, RES_BODY))  // tan   x 0.5..2.5
                .box(3f, 5.5f, 0f, 1f, 3f, 3f, plastic(904L, RES_B3))      // gold  x 2.5..3.5
                .box(4.5f, 5.5f, 0f, 2f, 3f, 3f, plastic(901L, RES_BODY))  // tan   x 3.5..5.5
                // +X lead: top at body-bottom-end (x5.5,y6), down-and-out to the base (x5.5+√5, y4)
                .quad(new Vector3(5.5f, 6f, -0.5f), new Vector3(5.5f + s5, 4f, -0.5f),
                        new Vector3(5.5f + s5, 4f, 0.5f), new Vector3(5.5f, 6f, 0.5f), fence(905L), 3, 1)
                // -X lead (mirror)
                .quad(new Vector3(-5.5f, 6f, 0.5f), new Vector3(-5.5f - s5, 4f, 0.5f),
                        new Vector3(-5.5f - s5, 4f, -0.5f), new Vector3(-5.5f, 6f, -0.5f), fence(905L), 3, 1)
                .build();
    }

    /**
     * Diode: the resistor's one-way sibling — the same standard base and the same two tilted metal lead-plates,
     * but the banded tan body is replaced by a single <b>black blob</b> (11×3×3, y4..7). Direction is printed on
     * the base: the red trace line carries an arrowhead (see {@code diodeTrace()}) pointing in the flow
     * direction (+x, toward the lead the current exits). Lead quads reuse the resistor's exact geometry + paint
     * (seed 905) so their sprites dedupe.
     */
    private ComponentModel buildDiode(Color[] pal) {
        float s5 = (float) Math.sqrt(5.0);
        return base("diode", pal, diodeTrace())
                .box(0f, 5.5f, 0f, 11f, 3f, 3f, plastic(921L, CAP_BODY))   // black blob x -5.5..5.5, y4..7
                .quad(new Vector3(5.5f, 6f, -0.5f), new Vector3(5.5f + s5, 4f, -0.5f),
                        new Vector3(5.5f + s5, 4f, 0.5f), new Vector3(5.5f, 6f, 0.5f), fence(905L), 3, 1)
                .quad(new Vector3(-5.5f, 6f, 0.5f), new Vector3(-5.5f - s5, 4f, 0.5f),
                        new Vector3(-5.5f - s5, 4f, -0.5f), new Vector3(-5.5f, 6f, -0.5f), fence(905L), 3, 1)
                .build();
    }

    /**
     * LED: the standard base + an incandescent-style light <b>bulb</b>, drawn as a BASE GREYSCALE texture and
     * <b>tinted by the component-entity colour</b> (so every LED colour reuses one texture). A metal <b>tube</b>
     * (5×5×2 screw base, y4..6, untinted steel), a <b>solid inner core</b> (5×5×5, y7..12, tinted), and a
     * <b>translucent outer core</b> (7×7×7, y6..13, tinted + alpha — drawn in the blended pass) around it.
     */
    private ComponentModel buildLed(Color[] pal) {
        return base("led", pal, whiteTrace())
                .box(0f, 5f, 0f, 5f, 2f, 5f, tube(913L))                       // metal tube (screw base) y4..6
                .box(0f, 9f, 0f, 5f, 6f, 5f, bulbCore(914L), true, false)      // solid inner core y6..12, ON the tube (tinted)
                .box(0f, 9.5f, 0f, 7f, 7f, 7f, bulbGlass(915L), true, true)    // translucent outer core y6..13 (tinted)
                .build();
    }

    /**
     * Capacitor (Snap-Circuits form): the SAME base every part has — a <b>recolourable</b> plastic body 33×9×4
     * ({@code pal} rim + white band + rim) with three metal snap studs at −12/0/+12, sitting flat on the board. Its own
     * feature is a black box ({@code w}×{@code w} × {@code h}) on the body's top, raised by two <b>metallic</b>
     * 0-thickness legs (1 wide in Z, {@code legH} tall) that <b>face each other</b> (0-thick in X), 3 apart.
     * Base rims reuse seeds 101/202/303 → they dedupe with the other parts' bodies of the same colour.
     */
    private ComponentModel buildCapacitor(Color[] pal, float w, float h, float legH, long seed) {
        float top = 4f;                        // body y0..4 (rim + white band + rim), like the switches
        float cy = top + legH + h / 2f;        // black box centre Y (legs bridge body-top → box-bottom)
        float r = Math.max(1f, Math.abs(0.5f / L) * (w / 2f) + Math.abs(0.7f / L) * (h / 2f)
                + Math.abs(0.4f / L) * (w / 2f));
        PaletteDither.Paint black = new PaletteDither.Paint(CAP_BODY, Color.WHITE, 2, 0.3f, false, seed + 1, 0f, cy, 0f, r, 1f);
        PaletteDither.Paint metal = new PaletteDither.Paint(STEEL, Color.WHITE, 1, 1.6f, true, seed + 3, 0f, cy, 0f, r, 1f);
        return studs(rims(ComponentModel.of("capacitor"), pal, capTrace())
                .box(-1.5f, top + legH / 2f, 0f, 0f, legH, 1f, metal)               // left leg  (faces +X)
                .box(1.5f, top + legH / 2f, 0f, 0f, legH, 1f, metal)                // right leg (faces -X)
                .box(0f, cy, 0f, w, h, w, black))                                   // black box on top
                .build();
    }

    /** Slide switch: body around a 6x4 hole, steel fence (well 4x2), black well floor, 2 studs, + slider. */
    private ComponentModel buildSwitch(Color[] pal) {
        float hx0 = OX - 3f, hx1 = OX + 3f, hz0 = OZ - 2f, hz1 = OZ + 2f;
        return studs(ComponentModel.of("slide_switch")
                .box(0f, 0.5f, 0f, 33f, 1f, 9f, plastic(101L, pal))                    // green y0..1
                .box(0f, 2f, 0f, 33f, 2f, 9f, band(102L))                              // white band y1..3
                .box((-16.5f + hx0) / 2f, 3.5f, 0f, hx0 + 16.5f, 1f, 9f, plastic(121L, pal), whiteTrace()) // left strip (trace)
                .box((hx1 + 16.5f) / 2f, 3.5f, 0f, 16.5f - hx1, 1f, 9f, plastic(122L, pal), whiteTrace())  // right strip (trace)
                .box(OX, 3.5f, (-4.5f + hz0) / 2f, 6f, 1f, hz0 + 4.5f, plastic(123L, pal))   // front strip
                .box(OX, 3.5f, (hz1 + 4.5f) / 2f, 6f, 1f, 4.5f - hz1, plastic(124L, pal))    // back strip
                .box(hx0 + 0.5f, 4f, OZ, 1f, 2f, 4f, fence(210L))                      // fence left (y3..5)
                .box(hx1 - 0.5f, 4f, OZ, 1f, 2f, 4f, fence(220L))                      // fence right
                .box(OX, 4f, hz0 + 0.5f, 4f, 2f, 1f, fence(230L))                      // fence front
                .box(OX, 4f, hz1 - 0.5f, 4f, 2f, 1f, fence(240L))                      // fence back
                .box(OX, 3.5f, OZ, 4f, 1f, 2f, knob(250L)))                            // black well floor y3..4
                .movable(slider, OX, 5f, OZ, BindingSpec.translate("slide", 1f, 0f, 0f))
                .build();
    }

    /**
     * Press switch: same body/band/studs as the slide switch, but the mechanism is <b>centred on the body</b>
     * (not the +0.5 slide offset): a square <b>5×5 hole</b> ringed by a 1px steel fence (well interior 3×3) and
     * a black well floor, plus a <b>3×3 press button</b> (movable) that plunges straight down. Odd widths (5,3)
     * keep every edge on the odd 33×9 body's texel grid. The button's {@code press} 0→1 drops it 2 in Y.
     */
    private ComponentModel buildPressSwitch(Color[] pal) {
        float hx0 = -2.5f, hx1 = 2.5f, hz0 = -2.5f, hz1 = 2.5f; // 5×5 hole, centred on the body (0,0)
        return studs(ComponentModel.of("press_switch")
                .box(0f, 0.5f, 0f, 33f, 1f, 9f, plastic(101L, pal))                    // green y0..1
                .box(0f, 2f, 0f, 33f, 2f, 9f, band(102L))                              // white band y1..3
                .box((-16.5f + hx0) / 2f, 3.5f, 0f, hx0 + 16.5f, 1f, 9f, plastic(121L, pal), whiteTrace()) // left w11 (trace)
                .box((hx1 + 16.5f) / 2f, 3.5f, 0f, 16.5f - hx1, 1f, 9f, plastic(122L, pal), whiteTrace())  // right w11 (trace)
                .box(0f, 3.5f, (-4.5f + hz0) / 2f, 5f, 1f, hz0 + 4.5f, plastic(123L, pal))   // front strip d2
                .box(0f, 3.5f, (hz1 + 4.5f) / 2f, 5f, 1f, 4.5f - hz1, plastic(124L, pal))    // back strip d2
                .box(hx0 + 0.5f, 4f, 0f, 1f, 2f, 5f, fence(210L))                      // fence left (y3..5)
                .box(hx1 - 0.5f, 4f, 0f, 1f, 2f, 5f, fence(220L))                      // fence right
                .box(0f, 4f, hz0 + 0.5f, 5f, 2f, 1f, fence(230L))                      // fence front
                .box(0f, 4f, hz1 - 0.5f, 5f, 2f, 1f, fence(240L))                      // fence back
                .box(0f, 3.5f, 0f, 3f, 1f, 3f, knob(250L)))                            // black well floor 3×3
                .movable(button, 0f, 5.5f, 0f, BindingSpec.translate("press", 0f, -2f, 0f))
                .build();
    }

    // ---- datagen registry: the named set the model-gen writes to JSON, and the loader reads back ----

    /** Every component model by its unique datagen id (the JSON file name). Movables reference {@link #partTypes}. */
    Map<String, ComponentModel> registry() {
        Map<String, ComponentModel> m = new LinkedHashMap<>();
        for (int c = 0; c < PLASTIC_NAME.length; c++) m.put("base_" + PLASTIC_NAME[c], bases[c]);
        String[] cap = {"big", "medium", "small"};
        for (int s = 0; s < cap.length; s++) m.put("capacitor_" + cap[s], capacitorSizes[s]);
        for (int c = 0; c < PLASTIC_NAME.length; c++) {
            m.put("switch_" + PLASTIC_NAME[c], switches[c]);
            m.put("press_" + PLASTIC_NAME[c], pressSwitches[c]);
            m.put("resistor_" + PLASTIC_NAME[c], resistors[c]);
            m.put("diode_" + PLASTIC_NAME[c], diodes[c]);
            m.put("led_" + PLASTIC_NAME[c], leds[c]);
        }
        for (int n = WIRE_MIN; n <= WIRE_MAX; n++) {
            m.put("wire_" + n, wires[n - WIRE_MIN]); // one type, size = component state → wire_<n>
        }
        return m;
    }

    /** The movable part-types (id → boxes) that component models' movables borrow, generated as their own models. */
    Map<String, PartType> partTypes() {
        Map<String, PartType> m = new LinkedHashMap<>();
        m.put(slider.id(), slider);   // "slider"
        m.put(button.id(), button);   // "button"
        return m;
    }
}
