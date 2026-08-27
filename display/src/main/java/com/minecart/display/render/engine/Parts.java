package com.minecart.display.render.engine;

import com.badlogic.gdx.graphics.Color;

import java.util.List;

/**
 * The part + component library, as pure data (no GL). Geometry AND texture recipe match {@code PreviewPart}
 * exactly — each box carries the same {@link PaletteDither.Paint} (palette, grain, per-box seed, object-space
 * shade centre/radius) as the corresponding {@code PreviewPart.box(...)} call, so the baked atlas sprites are
 * pixel-identical to the {@code ModelPreviewApp} render. Static boxes merge into the neighbour-culled scene
 * mesh; the slider is the only movable part-type (instanced).
 */
final class Parts {

    // Series shading frame (== ModelPreviewApp.SHADING + PreviewPart's HALF_X/Y/Z and stud extents).
    private static final float L = (float) Math.sqrt(0.5 * 0.5 + 0.7 * 0.7 + 0.4 * 0.4);
    private static final float SHADE_R =
            Math.abs(0.5f / L) * 12.5f + Math.abs(0.7f / L) * 3f + Math.abs(0.4f / L) * 4.5f;
    private static final float STUD_R = Math.max(1f,
            Math.abs(0.5f / L) * 1.5f + Math.abs(0.7f / L) * 0.5f + Math.abs(0.4f / L) * 1.5f);

    private static final Color[] LIME = PaletteDither.rampHsv(85f, 0.92f, 0.80f);   // PlasticColors SET[3] "lime"
    private static final Color[] BAND = PaletteDither.grays(6, 0.85f, 1.0f);        // white plastic band
    private static final Color[] STEEL = PaletteDither.steelBlue();                 // metal
    private static final Color[] KNOB = PaletteDither.ramp(new Color(0.12f, 0.12f, 0.14f, 1f)); // near-black
    private static final Color BAND_WHITE = new Color(0.97f, 0.97f, 0.96f, 1f);     // band diffuse tint

    private static final float OX = 0.5f, OZ = 0.5f; // slide-switch centre offset, kept from PreviewPart

    final PartType slider; // the only movable part-type
    final ComponentModel capacitor;
    final ComponentModel slideSwitch;

    // Paint factories — one per PreviewPart material profile (palette, diffuse, grain, zeroWeight, ordered).
    private static PaletteDither.Paint plastic(long seed) {
        return new PaletteDither.Paint(LIME, Color.WHITE, 2, 0.3f, false, seed, 0f, 2f, 0f, SHADE_R);
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
        // Slider (movable): shaded at PreviewPart's stem rest (ox-1, 5, oz) so its texture matches, even though
        // its mesh is a local 2×2×2 the renderer places via the animation.
        slider = new PartType("slider", List.of(
                new PartMesh.Box(0f, 0f, 0f, 2f, 2f, 2f, knob(260L), -0.5f, 5f, 0.5f)));

        // Capacitor: green rims [0,1] & [3,4], white band [1,3], two steel studs (seeds 101/202/303/404).
        capacitor = ComponentModel.of("capacitor")
                .box(0f, 0.5f, 0f, 25f, 1f, 9f, plastic(101L))
                .box(0f, 3.5f, 0f, 25f, 1f, 9f, plastic(202L))
                .box(0f, 2f, 0f, 25f, 2f, 9f, band(303L))
                .box(-8f, 4.5f, 0f, 3f, 1f, 3f, stud(404L, -8f, 4.5f, 0f))
                .box(8f, 4.5f, 0f, 3f, 1f, 3f, stud(404L, 8f, 4.5f, 0f))
                .build();

        // Slide switch: green body around a 6x4 hole, steel fence (well 4x2), black well floor, 2 studs
        // (static) + a 2x2 black slider (movable). Seeds mirror PreviewPart's switch box() calls.
        float hx0 = OX - 3f, hx1 = OX + 3f, hz0 = OZ - 2f, hz1 = OZ + 2f;
        slideSwitch = ComponentModel.of("slide_switch")
                .box(0f, 0.5f, 0f, 25f, 1f, 9f, plastic(101L))                         // green y0..1
                .box(0f, 2f, 0f, 25f, 2f, 9f, band(102L))                              // white band y1..3
                .box((-12.5f + hx0) / 2f, 3.5f, 0f, hx0 + 12.5f, 1f, 9f, plastic(121L)) // top green: left strip
                .box((hx1 + 12.5f) / 2f, 3.5f, 0f, 12.5f - hx1, 1f, 9f, plastic(122L))  // right strip
                .box(OX, 3.5f, (-4.5f + hz0) / 2f, 6f, 1f, hz0 + 4.5f, plastic(123L))   // front strip
                .box(OX, 3.5f, (hz1 + 4.5f) / 2f, 6f, 1f, 4.5f - hz1, plastic(124L))    // back strip
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
}
