package com.minecart.display.render.engine;

import com.badlogic.gdx.graphics.Color;

import java.util.List;

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
            Math.abs(0.5f / L) * 12.5f + Math.abs(0.7f / L) * 3f + Math.abs(0.4f / L) * 4.5f;
    private static final float STUD_R = Math.max(1f,
            Math.abs(0.5f / L) * 1.5f + Math.abs(0.7f / L) * 0.5f + Math.abs(0.4f / L) * 1.5f);

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
    private static final Color BAND_WHITE = new Color(0.97f, 0.97f, 0.96f, 1f);     // band diffuse tint

    private static final float OX = 0.5f, OZ = 0.5f; // slide-switch centre offset, kept from PreviewPart

    /** Capacitor sizes: {body W×W footprint, body height, leg height}. Leg is 1 wide × legH tall × 0 thick. */
    static final float[][] CAP_SIZES = {{7f, 9f, 2f}, {5f, 6f, 3f}, {3f, 4f, 4f}}; // big, medium, small

    final PartType slider;                              // slide switch's mover (colour-independent)
    final PartType button;                              // press switch's plunger (colour-independent)
    final ComponentModel[] capacitors = new ComponentModel[CAP_SIZES.length]; // big, medium, small
    final ComponentModel[] switches = new ComponentModel[PLASTIC_HSV.length];
    final ComponentModel[] pressSwitches = new ComponentModel[PLASTIC_HSV.length];

    // Paint factories — plastic is per-colour; the rest are shared across colours.
    private static PaletteDither.Paint plastic(long seed, Color[] pal) {
        return new PaletteDither.Paint(pal, Color.WHITE, 2, 0.3f, false, seed, 0f, 2f, 0f, SHADE_R);
    }

    private static PaletteDither.Paint band(long seed) {
        return new PaletteDither.Paint(BAND, BAND_WHITE, 1, 0.3f, false, seed, 0f, 2f, 0f, SHADE_R);
    }

    private static PaletteDither.Paint fence(long seed) {
        return new PaletteDither.Paint(STEEL, Color.WHITE, 1, 1.6f, true, seed, 0f, 2f, 0f, SHADE_R);
    }

    private static PaletteDither.Paint stud(long seed, float cx, float cy, float cz) {
        return new PaletteDither.Paint(STEEL, Color.WHITE, 1, 1.6f, true, seed, cx, cy, cz, STUD_R);
    }

    private static PaletteDither.Paint knob(long seed) {
        return new PaletteDither.Paint(KNOB, Color.WHITE, 2, 0.3f, false, seed, 0f, 2f, 0f, SHADE_R);
    }

    Parts() {
        slider = new PartType("slider", List.of(
                new PartMesh.Box(0f, 0f, 0f, 2f, 2f, 2f, knob(260L), -0.5f, 5f, 0.5f)));
        // Press button: a 3×3 plunger, height 3, centred on the body, resting with its top 3px above the body
        // top (y4 → 7). Pressing (channel "press" 0→1) drops it 2 in Y so its top is 1px above (y5).
        button = new PartType("button", List.of(
                new PartMesh.Box(0f, 0f, 0f, 3f, 3f, 3f, knob(360L), 0f, 5.5f, 0f)));
        for (int s = 0; s < CAP_SIZES.length; s++) {
            capacitors[s] = buildCapacitor(CAP_SIZES[s][0], CAP_SIZES[s][1], CAP_SIZES[s][2], 500L + s * 10L);
        }
        for (int c = 0; c < PLASTIC_HSV.length; c++) {
            Color[] pal = PaletteDither.rampHsv(PLASTIC_HSV[c][0], PLASTIC_HSV[c][1], PLASTIC_HSV[c][2]);
            switches[c] = buildSwitch(pal);
            pressSwitches[c] = buildPressSwitch(pal);
        }
    }

    /**
     * Capacitor: a big black rectangular box (footprint {@code w}×{@code w}, height {@code h}) with noise +
     * shading, standing on a black <b>basement</b> slab (y0..1), raised by two <b>metallic</b> 0-thickness legs
     * (each 1 wide in Z, {@code legH} tall) that <b>face each other</b> (0-thick in X → broad faces point
     * inward), 3 apart. One object-space shade frame (body centre) so the gradient spans the whole unit.
     */
    private ComponentModel buildCapacitor(float w, float h, float legH, long seed) {
        float baseTop = 1f;                    // basement occupies y0..1 (sits on the board)
        float bodyBottom = baseTop + legH;     // legs bridge basement-top → body-bottom
        float cy = bodyBottom + h / 2f;        // body centre Y
        float r = Math.max(1f, Math.abs(0.5f / L) * (w / 2f) + Math.abs(0.7f / L) * (h / 2f)
                + Math.abs(0.4f / L) * (w / 2f));
        PaletteDither.Paint black = new PaletteDither.Paint(CAP_BODY, Color.WHITE, 2, 0.3f, false, seed + 1, 0f, cy, 0f, r);
        PaletteDither.Paint metal = new PaletteDither.Paint(STEEL, Color.WHITE, 1, 1.6f, true, seed + 3, 0f, cy, 0f, r);
        return ComponentModel.of("capacitor")
                .box(0f, 0.5f, 0f, w, 1f, w, black)                          // basement slab y0..1
                .box(-1.5f, baseTop + legH / 2f, 0f, 0f, legH, 1f, metal)    // left leg  (0-thick in X, faces +X)
                .box(1.5f, baseTop + legH / 2f, 0f, 0f, legH, 1f, metal)     // right leg (faces -X) — 3 apart
                .box(0f, cy, 0f, w, h, w, black)                            // black body on top
                .build();
    }

    /** Slide switch: body around a 6x4 hole, steel fence (well 4x2), black well floor, 2 studs, + slider. */
    private ComponentModel buildSwitch(Color[] pal) {
        float hx0 = OX - 3f, hx1 = OX + 3f, hz0 = OZ - 2f, hz1 = OZ + 2f;
        return ComponentModel.of("slide_switch")
                .box(0f, 0.5f, 0f, 25f, 1f, 9f, plastic(101L, pal))                    // green y0..1
                .box(0f, 2f, 0f, 25f, 2f, 9f, band(102L))                              // white band y1..3
                .box((-12.5f + hx0) / 2f, 3.5f, 0f, hx0 + 12.5f, 1f, 9f, plastic(121L, pal)) // left strip
                .box((hx1 + 12.5f) / 2f, 3.5f, 0f, 12.5f - hx1, 1f, 9f, plastic(122L, pal))  // right strip
                .box(OX, 3.5f, (-4.5f + hz0) / 2f, 6f, 1f, hz0 + 4.5f, plastic(123L, pal))   // front strip
                .box(OX, 3.5f, (hz1 + 4.5f) / 2f, 6f, 1f, 4.5f - hz1, plastic(124L, pal))    // back strip
                .box(hx0 + 0.5f, 4f, OZ, 1f, 2f, 4f, fence(210L))                      // fence left (y3..5)
                .box(hx1 - 0.5f, 4f, OZ, 1f, 2f, 4f, fence(220L))                      // fence right
                .box(OX, 4f, hz0 + 0.5f, 4f, 2f, 1f, fence(230L))                      // fence front
                .box(OX, 4f, hz1 - 0.5f, 4f, 2f, 1f, fence(240L))                      // fence back
                .box(OX, 3.5f, OZ, 4f, 1f, 2f, knob(250L))                             // black well floor y3..4
                .box(-8f, 4.5f, 0f, 3f, 1f, 3f, stud(404L, -8f, 4.5f, 0f))             // stud
                .box(8f, 4.5f, 0f, 3f, 1f, 3f, stud(404L, 8f, 4.5f, 0f))
                .movable(slider, OX, 5f, OZ, MovableBinding.translate("slide", 1f, 0f, 0f))
                .build();
    }

    /**
     * Press switch: same body/band/studs as the slide switch, but the mechanism is <b>centred on the body</b>
     * (not the +0.5 slide offset): a square <b>5×5 hole</b> ringed by a 1px steel fence (well interior 3×3) and
     * a black well floor, plus a <b>3×3 press button</b> (movable) that plunges straight down. Odd widths (5,3)
     * keep every edge on the odd 25×9 body's texel grid. The button's {@code press} 0→1 drops it 2 in Y.
     */
    private ComponentModel buildPressSwitch(Color[] pal) {
        float hx0 = -2.5f, hx1 = 2.5f, hz0 = -2.5f, hz1 = 2.5f; // 5×5 hole, centred on the body (0,0)
        return ComponentModel.of("press_switch")
                .box(0f, 0.5f, 0f, 25f, 1f, 9f, plastic(101L, pal))                    // green y0..1
                .box(0f, 2f, 0f, 25f, 2f, 9f, band(102L))                              // white band y1..3
                .box((-12.5f + hx0) / 2f, 3.5f, 0f, hx0 + 12.5f, 1f, 9f, plastic(121L, pal)) // top green: left w10
                .box((hx1 + 12.5f) / 2f, 3.5f, 0f, 12.5f - hx1, 1f, 9f, plastic(122L, pal))  // right w10
                .box(0f, 3.5f, (-4.5f + hz0) / 2f, 5f, 1f, hz0 + 4.5f, plastic(123L, pal))   // front strip d2
                .box(0f, 3.5f, (hz1 + 4.5f) / 2f, 5f, 1f, 4.5f - hz1, plastic(124L, pal))    // back strip d2
                .box(hx0 + 0.5f, 4f, 0f, 1f, 2f, 5f, fence(210L))                      // fence left (y3..5)
                .box(hx1 - 0.5f, 4f, 0f, 1f, 2f, 5f, fence(220L))                      // fence right
                .box(0f, 4f, hz0 + 0.5f, 5f, 2f, 1f, fence(230L))                      // fence front
                .box(0f, 4f, hz1 - 0.5f, 5f, 2f, 1f, fence(240L))                      // fence back
                .box(0f, 3.5f, 0f, 3f, 1f, 3f, knob(250L))                             // black well floor 3×3
                .box(-8f, 4.5f, 0f, 3f, 1f, 3f, stud(404L, -8f, 4.5f, 0f))             // stud
                .box(8f, 4.5f, 0f, 3f, 1f, 3f, stud(404L, 8f, 4.5f, 0f))
                .movable(button, 0f, 5.5f, 0f, MovableBinding.translate("press", 0f, -2f, 0f))
                .build();
    }
}
