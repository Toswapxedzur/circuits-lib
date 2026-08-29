package com.minecart.display.render.engine;

import com.badlogic.gdx.graphics.Color;
import com.minecart.snap.SnapSceneGeometry;

import java.util.ArrayList;
import java.util.List;

/**
 * The 3D snap board's <b>base board</b> — the plastic grid the parts snap onto — built for the instanced engine as
 * a flat surface <b>tiled</b> from one repeating cell plus a <b>stud</b> nub at every grid post (the classic Snap
 * look). Sizes vary per world, so a single full-board slab sprite can't be pre-generated; instead the surface is
 * tiled from ONE fixed 24×24 cell and studs from ONE fixed 3×3 nub.
 *
 * <p><b>Datagen, never runtime</b> (owner rule): the cell + stud textures are drawn once by {@link
 * SeedPartTextures} (which seeds {@link #seedBoxes()}) and committed as PNGs; the runtime only tiles committed
 * boxes. Every tile/stud shares its one sprite because {@link PaletteDither#faceName} hashes object corners
 * RELATIVE to the paint's shade centre — and each box sets its shade centre to its own centre, so a tile at any
 * world position resolves to the exact same sprite name as the origin seed tile.
 */
final class SnapBaseBoard {

    private SnapBaseBoard() {}

    private static final float CELL = SnapSceneGeometry.BUMP_SPACING;     // 24 — one grid cell
    private static final float THICK = SnapSceneGeometry.BASE_THICKNESS;  // 2  — slab thickness (below topY)
    private static final float STUD = SnapSceneGeometry.BUMP_WIDTH;       // 3  — stud footprint
    private static final float STUD_H = SnapSceneGeometry.BUMP_HEIGHT;    // 1  — stud height (above topY)

    // A muted blue-grey plastic board; studs a touch lighter so the grid reads.
    private static final Color BOARD = new Color(0.44f, 0.48f, 0.55f, 1f);
    private static final Color STUD_COLOR = new Color(0.56f, 0.60f, 0.66f, 1f);
    /** Board opacity — semi-transparent (owner spec), so the board reads as a glassy plate. */
    private static final float ALPHA = 0.55f;

    /** One surface cell, centred at world ({@code wx}, {@code wz}) with its top at {@code topY}. */
    private static PartMesh.Box cell(float wx, float wz, float topY) {
        float cy = topY - THICK / 2f;
        PaletteDither.Paint paint = new PaletteDither.Paint(PaletteDither.ramp(BOARD), Color.WHITE,
                2, 0.3f, false, 4501L, wx, cy, wz, CELL / 2f, 1f); // shade centre = own centre → shared sprite
        return PartMesh.Box.localAlpha(wx, cy, wz, CELL, THICK, CELL, paint, ALPHA);
    }

    /** One stud nub sitting on top of the surface at world ({@code wx}, {@code wz}), {@code topY} = surface top. */
    private static PartMesh.Box stud(float wx, float wz, float topY) {
        float cy = topY + STUD_H / 2f;
        PaletteDither.Paint paint = new PaletteDither.Paint(PaletteDither.ramp(STUD_COLOR), Color.WHITE,
                1, 0.3f, false, 4507L, wx, cy, wz, STUD, 1f); // shade centre = own centre → shared sprite
        return PartMesh.Box.localAlpha(wx, cy, wz, STUD, STUD_H, STUD, paint, ALPHA);
    }

    /**
     * The full base board for a {@code cols}×{@code rows} grid, top surface at {@code topY}: a tiled surface (one
     * extra cell of margin all round) plus a stud at every post. All boxes are world-space statics for the engine.
     */
    static List<PartMesh.Box> build(int cols, int rows, float topY) {
        List<PartMesh.Box> out = new ArrayList<>();
        for (int col = -1; col <= cols; col++) {
            for (int row = -1; row <= rows; row++) {
                out.add(cell(col * CELL, row * CELL, topY));
            }
        }
        for (int col = 0; col < cols; col++) {
            for (int row = 0; row < rows; row++) {
                out.add(stud(col * CELL, row * CELL, topY));
            }
        }
        return out;
    }

    /** The two distinct sprites the board needs (a cell + a stud at the origin) — for {@link SeedPartTextures}. */
    static List<PartMesh.Box> seedBoxes() {
        return List.of(cell(0f, 0f, 0f), stud(0f, 0f, 0f));
    }
}
