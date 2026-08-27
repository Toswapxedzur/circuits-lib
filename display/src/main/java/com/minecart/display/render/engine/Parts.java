package com.minecart.display.render.engine;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.utils.Disposable;

import java.util.List;

/**
 * The part + component library. Static geometry is declared as boxes on the {@link ComponentModel} (merged
 * into the scene mesh with neighbour culling); the switch's slider is the only movable part-type (instanced).
 * Geometry matches the pixel-perfect {@code PreviewPart} parts exactly, but the z-fighting fixes it needed
 * (fractional-{@code E} overlaps) are gone — occlusion drops internal faces, back-face culling handles seams.
 * Colours are flat palette base shades for now (per-face litFace dither needs a texture atlas — later).
 */
final class Parts implements Disposable {

    private static final Color LIME = new Color(0.55f, 0.82f, 0.16f, 1f);
    private static final Color WHITE = new Color(0.85f, 0.86f, 0.83f, 1f);
    private static final Color STEEL = new Color(0.55f, 0.61f, 0.69f, 1f);
    private static final Color BLACK = new Color(0.13f, 0.13f, 0.15f, 1f);

    private static final float OX = 0.5f, OZ = 0.5f; // slide-switch centre offset, kept from PreviewPart

    final PartType slider; // the only movable part-type

    final ComponentModel capacitor;
    final ComponentModel slideSwitch;

    Parts(int maxSliderInstances) {
        slider = new PartType("slider", PartMesh.of(List.of(
                new PartMesh.Box(0f, 0f, 0f, 2f, 2f, 2f, BLACK)), maxSliderInstances));

        // Capacitor: green rims [0,1] & [3,4], white band [1,3], two steel studs — all static.
        capacitor = ComponentModel.of("capacitor")
                .box(0f, 0.5f, 0f, 25f, 1f, 9f, LIME)
                .box(0f, 3.5f, 0f, 25f, 1f, 9f, LIME)
                .box(0f, 2f, 0f, 25f, 2f, 9f, WHITE)
                .box(-8f, 4.5f, 0f, 3f, 1f, 3f, STEEL)
                .box(8f, 4.5f, 0f, 3f, 1f, 3f, STEEL)
                .build();

        // Slide switch: green-sliced body around a 6x4 hole, steel fence (well 4x2), black floor, 2 studs
        // (static), + a 2x2 black slider (movable, slides X). Hole edges: x -2.5..3.5, z -1.5..2.5.
        float hx0 = OX - 3f, hx1 = OX + 3f, hz0 = OZ - 2f, hz1 = OZ + 2f;
        slideSwitch = ComponentModel.of("slide_switch")
                .box(0f, 0.5f, 0f, 25f, 1f, 9f, LIME)                                  // green y0..1
                .box(0f, 2f, 0f, 25f, 2f, 9f, WHITE)                                   // white band y1..3
                .box((-12.5f + hx0) / 2f, 3.5f, 0f, hx0 + 12.5f, 1f, 9f, LIME)         // top green: left strip
                .box((hx1 + 12.5f) / 2f, 3.5f, 0f, 12.5f - hx1, 1f, 9f, LIME)          // right strip
                .box(OX, 3.5f, (-4.5f + hz0) / 2f, 6f, 1f, hz0 + 4.5f, LIME)           // front strip
                .box(OX, 3.5f, (hz1 + 4.5f) / 2f, 6f, 1f, 4.5f - hz1, LIME)            // back strip
                .box(hx0 + 0.5f, 4f, OZ, 1f, 2f, 4f, STEEL)                            // fence left (y3..5)
                .box(hx1 - 0.5f, 4f, OZ, 1f, 2f, 4f, STEEL)                            // fence right
                .box(OX, 4f, hz0 + 0.5f, 4f, 2f, 1f, STEEL)                            // fence front
                .box(OX, 4f, hz1 - 0.5f, 4f, 2f, 1f, STEEL)                            // fence back
                .box(OX, 3.5f, OZ, 4f, 1f, 2f, BLACK)                                  // well floor y3..4
                .box(-8f, 4.5f, 0f, 3f, 1f, 3f, STEEL)                                 // stud
                .box(8f, 4.5f, 0f, 3f, 1f, 3f, STEEL)
                .movable(slider, OX, 5f, OZ, MovableBinding.translate("slide", 1f, 0f, 0f))
                .build();
    }

    @Override
    public void dispose() {
        slider.dispose();
    }
}
