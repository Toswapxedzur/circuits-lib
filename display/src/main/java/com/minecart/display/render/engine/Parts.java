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
    private static final Color BAND_WHITE = new Color(0.97f, 0.97f, 0.96f, 1f);     // band diffuse tint

    private static final float OX = 0.5f, OZ = 0.5f; // slide-switch centre offset, kept from PreviewPart

    final PartType slider;                              // slide switch's mover (colour-independent)
    final PartType button;                              // press switch's plunger (colour-independent)
    final ComponentModel[] capacitors = new ComponentModel[PLASTIC_HSV.length];
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
        // Press button: a 3×4 plunger (width 3, depth 4), height 3, resting with its top 3px above the body top
        // (y4 → 7). Shaded at that rest pose. Pressing (channel "press" 0→1) drops it 2 in Y so its top is 1px
        // above (y5). Width 3 in the 4-wide well → centred with a 0.5 margin left/right.
        button = new PartType("button", List.of(
                new PartMesh.Box(0f, 0f, 0f, 3f, 3f, 4f, knob(360L), OX, 5.5f, OZ)));
        for (int c = 0; c < PLASTIC_HSV.length; c++) {
            Color[] pal = PaletteDither.rampHsv(PLASTIC_HSV[c][0], PLASTIC_HSV[c][1], PLASTIC_HSV[c][2]);
            capacitors[c] = buildCapacitor(pal);
            switches[c] = buildSwitch(pal);
            pressSwitches[c] = buildPressSwitch(pal);
        }
    }

    /** Capacitor: green rims [0,1] & [3,4], white band [1,3], two steel studs (seeds 101/202/303/404). */
    private ComponentModel buildCapacitor(Color[] pal) {
        return ComponentModel.of("capacitor")
                .box(0f, 0.5f, 0f, 25f, 1f, 9f, plastic(101L, pal))
                .box(0f, 3.5f, 0f, 25f, 1f, 9f, plastic(202L, pal))
                .box(0f, 2f, 0f, 25f, 2f, 9f, band(303L))
                .box(-8f, 4.5f, 0f, 3f, 1f, 3f, stud(404L, -8f, 4.5f, 0f))
                .box(8f, 4.5f, 0f, 3f, 1f, 3f, stud(404L, 8f, 4.5f, 0f))
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
     * Press switch: same body/band/studs as the slide switch, but with a square <b>6×6 hole</b> ringed by a
     * <b>4×4 steel fence</b> (well interior 4×4) and a black well floor, plus a 4×4 press button (movable) that
     * plunges straight down. The button's {@code press} channel 0→1 drops it 2 in Y (top 3px→1px above the body).
     */
    private ComponentModel buildPressSwitch(Color[] pal) {
        float hx0 = OX - 3f, hx1 = OX + 3f, hz0 = OZ - 3f, hz1 = OZ + 3f; // 6×6 hole (x/z -2.5..3.5)
        return ComponentModel.of("press_switch")
                .box(0f, 0.5f, 0f, 25f, 1f, 9f, plastic(101L, pal))                    // green y0..1
                .box(0f, 2f, 0f, 25f, 2f, 9f, band(102L))                              // white band y1..3
                .box((-12.5f + hx0) / 2f, 3.5f, 0f, hx0 + 12.5f, 1f, 9f, plastic(121L, pal)) // top green: left
                .box((hx1 + 12.5f) / 2f, 3.5f, 0f, 12.5f - hx1, 1f, 9f, plastic(122L, pal))  // right
                .box(OX, 3.5f, (-4.5f + hz0) / 2f, 6f, 1f, hz0 + 4.5f, plastic(123L, pal))   // front strip
                .box(OX, 3.5f, (hz1 + 4.5f) / 2f, 6f, 1f, 4.5f - hz1, plastic(124L, pal))    // back strip
                .box(hx0 + 0.5f, 4f, OZ, 1f, 2f, 6f, fence(210L))                      // fence left (y3..5)
                .box(hx1 - 0.5f, 4f, OZ, 1f, 2f, 6f, fence(220L))                      // fence right
                .box(OX, 4f, hz0 + 0.5f, 6f, 2f, 1f, fence(230L))                      // fence front
                .box(OX, 4f, hz1 - 0.5f, 6f, 2f, 1f, fence(240L))                      // fence back
                .box(OX, 3.5f, OZ, 4f, 1f, 4f, knob(250L))                             // black well floor y3..4
                .box(-8f, 4.5f, 0f, 3f, 1f, 3f, stud(404L, -8f, 4.5f, 0f))             // stud
                .box(8f, 4.5f, 0f, 3f, 1f, 3f, stud(404L, 8f, 4.5f, 0f))
                .movable(button, OX, 5.5f, OZ, MovableBinding.translate("press", 0f, -2f, 0f))
                .build();
    }
}
