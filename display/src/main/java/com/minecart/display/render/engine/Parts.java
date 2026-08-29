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
            {112f, 0.97f, 0.48f}, // deep green (owner-picked: bluer than lime, sat up, value down hard)
            {0f, 0f, 0.72f},      // light gray
            {0f, 0f, 0.35f},      // dark gray
            {0f, 0f, 1.00f},      // white (whole body white — band-matched palette, see WHITE_NAME)
    };
    static final String[] PLASTIC_NAME = {
            "red", "orange", "yellow", "lime", "teal", "cyan", "azure", "blue", "violet", "purple", "pink",
            "green", "lgray", "dgray", "white"};

    /** The white piece renders its WHOLE body white — its rim palette is the band's grays (not ramp-white,
     *  which spans 0.75–1.0 and would seam against the 0.85–1.0 band). Matched by name here. */
    private static final String WHITE_NAME = "white";

    private static final Color[] BAND = PaletteDither.grays(6, 0.85f, 1.0f);        // white plastic band
    private static final Color[] STEEL = PaletteDither.steelBlue();                 // metal
    // THE series black (owner 2026-08-28): "muddy" charcoal, black but clearly visible — the lighting and
    // palette noise must read. Every black plastic surface (capacitor body, knobs/buttons, well floors, diode
    // blob) uses THIS ramp; never a darker "void" one.
    private static final Color[] SERIES_BLACK = PaletteDither.ramp(new Color(0.19f, 0.19f, 0.22f, 1f));
    private static final Color[] CAP_BASE = PaletteDither.rampHsv(160f, 0.92f, 0.80f); // teal snap base plastic
    private static final Color[] RES_BODY = PaletteDither.ramp(new Color(0.82f, 0.68f, 0.45f, 1f)); // tan resistor body
    private static final Color[] RES_B1 = PaletteDither.ramp(new Color(0.35f, 0.20f, 0.10f, 1f));   // band: brown
    private static final Color[] RES_B2 = PaletteDither.ramp(new Color(0.72f, 0.10f, 0.10f, 1f));   // band: red
    private static final Color[] RES_B3 = PaletteDither.ramp(new Color(0.82f, 0.62f, 0.18f, 1f));   // band: gold
    private static final Color[] BULB_CORE = PaletteDither.grays(6, 0.60f, 1.00f);  // greyscale inner glow (TINTED)
    private static final Color[] BULB_GLASS = PaletteDither.grays(6, 0.70f, 1.00f); // greyscale glass (TINTED + translucent)
    // White plastic body — the band's grays (0.85–1.0), so the white piece + lamp walls are seamlessly white.
    private static final Color[] WHITE_PLASTIC = PaletteDither.grays(7, 0.85f, 1.0f);
    private static final Color[] LAMP_GLASS = PaletteDither.grays(6, 0.72f, 1.00f); // translucent warm-white film/cap
    private static final Color WARM = new Color(1.0f, 0.95f, 0.82f, 1f);            // warm diffuse tint for the lamp
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
    final ComponentModel lamp;                                                     // white-encased bulb (single)
    final ComponentModel tee;                                                      // T-base barebones (triangular 4-port)
    final ComponentModel transistorNpn;                                            // red base, cube top-black/bottom-white
    final ComponentModel transistorPnp;                                            // dark-green base, cube top-white/bottom-black
    final ComponentModel batteryCell;                                              // loose battery entity (orange+black cell)
    final ComponentModel slab;                                                     // neutral grey unit slab (scenery, scaled via pose)
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
        return new PaletteDither.Paint(SERIES_BLACK, Color.WHITE, 2, 0.3f, false, seed, 0f, 2f, 0f, SHADE_R, 1f);
    }

    // LED bulb paints — GREYSCALE bases (the colour comes from the component-entity tint), shade-centred on the bulb.
    private static PaletteDither.Paint bulbCore(long seed) {   // solid inner glow (tinted)
        return new PaletteDither.Paint(BULB_CORE, Color.WHITE, 2, 0.3f, false, seed, 0f, 8f, 0f, SHADE_R, 1f);
    }

    private static PaletteDither.Paint bulbGlass(long seed) {  // translucent outer glass (tinted, alpha 0.45)
        return new PaletteDither.Paint(BULB_GLASS, Color.WHITE, 2, 0.3f, false, seed, 0f, 8f, 0f, SHADE_R, 0.45f);
    }

    // Lamp paints — opaque white walls, a warm opaque emitter, and translucent warm caps (shade-centred up the tube).
    private static PaletteDither.Paint lampWall(long seed) {
        return new PaletteDither.Paint(WHITE_PLASTIC, BAND_WHITE, 1, 0.3f, false, seed, 0f, 7.5f, 0f, SHADE_R, 1f);
    }

    private static PaletteDither.Paint lampGlass(long seed) { // translucent warm film/cap (alpha 0.45)
        return new PaletteDither.Paint(LAMP_GLASS, WARM, 1, 0.3f, false, seed, 0f, 10f, 0f, SHADE_R, 0.45f);
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
            // The white piece uses the band's grays for its rims too, so the whole body is one seamless white.
            Color[] pal = WHITE_NAME.equals(PLASTIC_NAME[c]) ? WHITE_PLASTIC
                    : PaletteDither.rampHsv(PLASTIC_HSV[c][0], PLASTIC_HSV[c][1], PLASTIC_HSV[c][2]);
            bases[c] = base("base", pal).build(); // the blank snap base board, in every plastic colour
            switches[c] = buildSwitch(pal);
            pressSwitches[c] = buildPressSwitch(pal);
            resistors[c] = buildResistor(pal);
            diodes[c] = buildDiode(pal);
            leds[c] = buildLed(pal);
        }
        Color[] azure = PaletteDither.rampHsv(
                PLASTIC_HSV[WIRE_COLOR][0], PLASTIC_HSV[WIRE_COLOR][1], PLASTIC_HSV[WIRE_COLOR][2]);
        lamp = buildLamp(WHITE_PLASTIC);
        tee = buildTee(PaletteDither.rampHsv(PLASTIC_HSV[3][0], PLASTIC_HSV[3][1], PLASTIC_HSV[3][2])); // lime, demo
        transistorNpn = buildTransistor("transistor_npn",
                PaletteDither.rampHsv(PLASTIC_HSV[0][0], PLASTIC_HSV[0][1], PLASTIC_HSV[0][2]), true);   // red
        transistorPnp = buildTransistor("transistor_pnp",
                PaletteDither.rampHsv(PLASTIC_HSV[11][0], PLASTIC_HSV[11][1], PLASTIC_HSV[11][2]), false); // deep green
        batteryCell = buildBatteryCell();
        slab = ComponentModel.of("slab").box(0f, 0f, 0f, 1f, 1f, 1f,
                new PaletteDither.Paint(PaletteDither.grays(4, 0.32f, 0.48f), Color.WHITE, 1, 0.3f, false, 701L, 0f, 0f, 0f, 1f, 1f)).build();
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
        return socket(b, x, 0f);
    }

    /** The underside socket at an arbitrary grid point (x,z) — used by the T-base's stem-tip port, which sits
     *  off the z=0 line. Same 5×5 fence → inner 3×3 hollow → recessed metal floor as {@link #socket(ComponentModel.Builder, float)}. */
    private ComponentModel.Builder socket(ComponentModel.Builder b, float x, float z) {
        return b.box(x - 2f, -0.5f, z, 1f, 1f, 5f, fence(501L)) // fence: left wall  (full depth)
                .box(x + 2f, -0.5f, z, 1f, 1f, 5f, fence(501L)) // fence: right wall
                .box(x, -0.5f, z - 2f, 3f, 1f, 1f, fence(502L)) // fence: front wall (fills the gap)
                .box(x, -0.5f, z + 2f, 3f, 1f, 1f, fence(502L)) // fence: back wall
                .box(x, 0f, z, 3f, 0f, 3f, fence(503L));        // recessed metal floor (down-facing skin at y0)
    }

    /**
     * The <b>T-base</b> barebones — the shared body for the triangular 4-port parts (transistor, some switches,
     * …). Built exactly as the owner specified: the standard 3-cell bar (33×9), a <b>9×9 stem</b> extruded from
     * the centre toward the front (−z) to occupy a 4th cell, and <b>two 3×3 corner squares</b> stepping the
     * stem→bar notch so the T reads as an (approximate, no-diagonal) triangle. Ports (stud 3×3 + underside
     * socket) at the two bar ends (±12) and the stem tip (0,−12); the centre (0,0) is covered but unstudded by
     * default (like the bar's middle). Every box uses the shared rim/band/steel DNA so it recolours + dithers
     * like the rest of the series. The stem tip stud sits at z=−12 (footprint −13.5..−10.5), so the stem body
     * reaches z=−13.5 (length 9 from the bar's front edge at −4.5).
     */
    private ComponentModel buildTee(Color[] pal) {
        return teeBuilder("tee", pal, null).build();
    }

    /**
     * The T-base builder (returns the {@link ComponentModel.Builder} so callers like {@link #buildTransistor}
     * can add a mechanism before {@code build()}). Bar + 9-wide×12-deep stem (3px tip buffer) + two 6×6 corner
     * squares + 3 studs/sockets (bar ends ±12 + stem tip 0,−12). An optional {@code trace} is applied to BOTH
     * the bar's top rim and the stem's top rim, so each draws its portion of the printed line (the transistor's
     * T-trace: the bar line at z=0 lives on the bar rim, the −z branch spans both rims).
     */
    private ComponentModel.Builder teeBuilder(String id, Color[] pal, PartMesh.Trace trace) {
        ComponentModel.Builder b = rims(ComponentModel.of(id), pal, trace); // bar (33×9, trace on its top rim)
        // stem: 9-wide × 12-deep arm from the centre out to the front (centre z=−10.5) — depth 12 so the
        // stem-tip stud at z=−12 gets the same 3px buffer the bar ends have (stem front reaches −16.5).
        b = b.box(0f, 0.5f, -10.5f, 9f, 1f, 12f, plastic(111L, pal))        // stem bottom rim y0..1
                .box(0f, 3.5f, -10.5f, 9f, 1f, 12f, plastic(212L, pal), trace) // stem top rim y3..4 (trace branch)
                .box(0f, 2f, -10.5f, 9f, 2f, 12f, band(313L));              // stem white band y1..3
        for (float sx : new float[]{-7.5f, 7.5f}) {                         // two 6×6 corner squares against the stem
            b = b.box(sx, 0.5f, -7.5f, 6f, 1f, 6f, plastic(121L, pal))      // walls, on the bar front (z −4.5..−10.5)
                    .box(sx, 3.5f, -7.5f, 6f, 1f, 6f, plastic(222L, pal))
                    .box(sx, 2f, -7.5f, 6f, 2f, 6f, band(323L));            // → two-step slope 33→21→9 ≈ triangle
        }
        b = studs(b);                                                       // bar-end ports (±12,0)
        b = b.box(0f, 4.5f, -12f, 3f, 1f, 3f, stud(404L, 0f, 4.5f, -12f));  // stem-tip stud
        return socket(b, 0f, -12f);                                         // stem-tip socket
    }

    /** The transistor's white T-trace: the bar line between the ±12 studs + a −z branch out to the stem tip. */
    private static PartMesh.Trace transistorTrace() {
        return new PartMesh.Trace(TRACE_WHITE, false, false, 10.5f, 10.5f);
    }

    /**
     * A transistor on the T-base (NPN = red base, PNP = dark-green base). A <b>5×5×5 cube</b> sits centred on the
     * base top (y4..9), split 40/60 by height at y7: PNP = top WHITE (2px) + bottom BLACK (3px); <b>NPN reverses
     * it</b> (top black, bottom white). Three flat metal <b>legs</b> (1×3×0, TO-92 style) hang from the cube
     * front (z=−2.5) down into the base. The white T-trace connects the three studs.
     */
    private ComponentModel buildTransistor(String id, Color[] pal, boolean npn) {
        Color[] topPal = npn ? SERIES_BLACK : WHITE_PLASTIC;  // NPN top black / PNP top white
        Color[] botPal = npn ? WHITE_PLASTIC : SERIES_BLACK;  // NPN bottom white / PNP bottom black
        // Cube RAISED 3px off the base (bottom at y7) so its 3 legs show in the gap below (like a real
        // transistor standing on its legs). Cube y7..12, still split 40/60 by height at y10.
        ComponentModel.Builder b = teeBuilder(id, pal, transistorTrace())
                .box(0f, 8.5f, 0f, 5f, 3f, 5f, plastic(931L, botPal))   // cube bottom 3px (60%) y7..10
                .box(0f, 11f, 0f, 5f, 2f, 5f, plastic(932L, topPal));   // cube top 2px (40%) y10..12
        // 3 legs (1×3×0, y4..7) spread in DEPTH: outer legs 1px in from the cube's front depth-end (z=−1.5),
        // middle leg 4px in (z=+1.5) — the real TO-92 "middle lead bent back" look. Breadth stays 10101.
        b = b.box(-2f, 5.5f, -1.5f, 1f, 3f, 0f, fence(940L))            // left leg  (front, 1px from end)
                .box(2f, 5.5f, -1.5f, 1f, 3f, 0f, fence(940L))          // right leg (front, 1px from end)
                .box(0f, 5.5f, 1.5f, 1f, 3f, 0f, fence(940L));          // middle leg (4px in — pushed back)
        return b.build();
    }

    /**
     * A loose <b>battery cell</b> — the removable world ENTITY that pops out of a battery holder (the holder is
     * a separate "battery box" part; this cell is what tumbles as a physics entity). Snap-Circuits AA look: an
     * orange wrap over most of the length, a black band at the <b>+</b> end, and a small steel terminal nub.
     * Modelled as a 6×6 box lying along X (length 18), <b>centred on the origin</b> so its physics body (a
     * matching box) tumbles about its own centre. Blocky LEGO style, sharing the series' steel/plastic DNA.
     */
    private ComponentModel buildBatteryCell() {
        Color[] orange = PaletteDither.rampHsv(PLASTIC_HSV[1][0], PLASTIC_HSV[1][1], PLASTIC_HSV[1][2]);
        float r = shadeR(9f);                                       // half-length 9 along X
        PaletteDither.Paint wrap = new PaletteDither.Paint(orange, Color.WHITE, 2, 0.3f, false, 611L, 0f, 0f, 0f, r, 1f);
        PaletteDither.Paint capEnd = new PaletteDither.Paint(SERIES_BLACK, Color.WHITE, 2, 0.3f, false, 612L, 0f, 0f, 0f, r, 1f);
        PaletteDither.Paint nub = new PaletteDither.Paint(STEEL, Color.WHITE, 1, 1.6f, true, 613L, 0f, 0f, 0f, STUD_R, 1f);
        return ComponentModel.of("battery_cell")
                .box(-3f, 0f, 0f, 12f, 6f, 6f, wrap)                // orange wrap  x -9..3
                .box(6f, 0f, 0f, 6f, 6f, 6f, capEnd)                // black + end  x 3..9
                .box(9.5f, 0f, 0f, 1f, 2f, 2f, nub)                 // + terminal   x 9..10
                .build();
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
     * but the banded tan body is replaced by a <b>charcoal blob</b> (11×3×3, y4..7) whose <b>+x 3×3×3 end is
     * white</b> — the flow-direction mark (like a real diode's cathode band; current exits the white end). The
     * trace stays the plain red line. Lead quads reuse the resistor's exact geometry + paint (seed 905) so
     * their sprites dedupe.
     */
    private ComponentModel buildDiode(Color[] pal) {
        float s5 = (float) Math.sqrt(5.0);
        return base("diode", pal, redTrace())
                .box(-1.5f, 5.5f, 0f, 8f, 3f, 3f, plastic(921L, SERIES_BLACK)) // charcoal blob x -5.5..2.5, y4..7
                .box(4f, 5.5f, 0f, 3f, 3f, 3f, band(922L))                   // white 3×3×3 end x 2.5..5.5 (flow mark)
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
     * Lamp (Snap-Circuits L1, the white-encased variant): the standard base + a <b>white-plastic tube</b>
     * enclosing the light. The enclosure (opaque white fence) is <b>7px tall</b>: 7×7 (1px walls → inner 5×5
     * hollow), <b>y4..11</b>. The light is <b>ONE translucent film 5px tall</b> — a 5×5×5 block at the <b>top</b>
     * of the hollow, <b>y6..11</b>, its top flush with the wall rim so that top face IS the glowing cover (there
     * is NO separate cap). It lights up when the lamp is on; that on/off mechanism is not yet wired, so it
     * renders as a static translucent block. Walls are opaque white plastic; the film is translucent (blended
     * pass). All features odd-width, centred on 0.
     */
    private ComponentModel buildLamp(Color[] pal) {
        return base("lamp", pal, whiteTrace())
                // The tube = 4 ZERO-THICKNESS white-plastic wall panels, 7×7, 7px tall (y4..11)
                .box(-3.5f, 7.5f, 0f, 0f, 7f, 7f, lampWall(931L))   // left panel  x=-3.5 (7 tall × 7 deep)
                .box(3.5f, 7.5f, 0f, 0f, 7f, 7f, lampWall(931L))    // right panel x= 3.5
                .box(0f, 7.5f, -3.5f, 7f, 7f, 0f, lampWall(932L))   // front panel z=-3.5 (7 wide × 7 tall)
                .box(0f, 7.5f, 3.5f, 7f, 7f, 0f, lampWall(932L))    // back panel  z= 3.5
                // the thin film = a ZERO-THICKNESS translucent horizontal plate, 7×7, at y9 (5px above the base)
                .box(0f, 9f, 0f, 7f, 0f, 7f, lampGlass(934L), false, true)
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
        PaletteDither.Paint black = new PaletteDither.Paint(SERIES_BLACK, Color.WHITE, 2, 0.3f, false, seed + 1, 0f, cy, 0f, r, 1f);
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
        m.put("lamp", lamp); // single white-encased bulb
        m.put("tee", tee);   // T-base barebones (triangular 4-port shape)
        m.put("transistor_npn", transistorNpn); // red, cube top-black/bottom-white
        m.put("transistor_pnp", transistorPnp); // dark-green, cube top-white/bottom-black
        m.put("battery_cell", batteryCell); // loose battery entity (orange+black cell)
        m.put("slab", slab); // neutral grey scenery slab (unit box, scaled via pose)
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
