package com.minecart.display.render.snap;

import com.minecart.snap.AllSnapParts;
import com.minecart.snap.Facing;
import com.minecart.snap.SnapBoard;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the board→3D box mapping (no GL): a part's bar is centred between its two posts at the right
 * height, oriented along its facing, and the base slab + post markers are emitted.
 */
class SnapSceneGeometryTest {

    private static final float EPS = 1e-4f;

    @Test
    void horizontalBatteryBarIsCentredBetweenPostsAtHalfHeight() {
        AllSnapParts.init();
        SnapBoard board = new SnapBoard(1, 1, 1);
        board.place(AllSnapParts.SNAP_BATTERY, 0, 0, 0, Facing.EAST, 2.0); // A(0,0) -> B(1,0)

        BoxSpec bar = SnapSceneGeometry.partBox(board.snapshot().get(0));
        assertEquals(BoxSpec.Category.BATTERY, bar.category());
        // Centre between (0,0) and (1,0) in cells => x = 0.5*CELL, z = 0; y = half a part height.
        assertEquals(0.5f * SnapSceneGeometry.CELL, bar.cx(), EPS);
        assertEquals(0f, bar.cz(), EPS);
        assertEquals(SnapSceneGeometry.PART_HEIGHT / 2f, bar.cy(), EPS);
        // Runs one full cell along X (the facing axis); thin along Z; PART_HEIGHT tall.
        assertEquals(SnapSceneGeometry.CELL, bar.sizeX(), EPS);
        assertEquals(SnapSceneGeometry.PART_CROSS, bar.sizeZ(), EPS);
        assertEquals(SnapSceneGeometry.PART_HEIGHT, bar.sizeY(), EPS);
    }

    @Test
    void verticalPartRunsAlongZ() {
        AllSnapParts.init();
        SnapBoard board = new SnapBoard(1, 1, 1);
        board.place(AllSnapParts.SNAP_WIRE, 0, 0, 0, Facing.NORTH); // A(0,0) -> B(0,1)

        BoxSpec bar = SnapSceneGeometry.partBox(board.snapshot().get(0));
        assertEquals(SnapSceneGeometry.PART_CROSS, bar.sizeX(), EPS);
        assertEquals(SnapSceneGeometry.CELL, bar.sizeZ(), EPS);
        assertEquals(0f, bar.cx(), EPS);
        assertEquals(0.5f * SnapSceneGeometry.CELL, bar.cz(), EPS);
    }

    @Test
    void secondLayerRaisesTheBar() {
        AllSnapParts.init();
        SnapBoard board = new SnapBoard(1, 1, 2);
        board.place(AllSnapParts.SNAP_WIRE, 0, 0, 1, Facing.EAST); // layer 1

        BoxSpec bar = SnapSceneGeometry.partBox(board.snapshot().get(0));
        assertEquals(1 * SnapSceneGeometry.PART_HEIGHT + SnapSceneGeometry.PART_HEIGHT / 2f, bar.cy(), EPS);
    }

    @Test
    void sceneIncludesBaseAndPostMarkers() {
        SnapBoard board = new SnapBoard(2, 2, 1);
        List<BoxSpec> boxes = SnapSceneGeometry.build(board);

        long base = boxes.stream().filter(b -> b.category() == BoxSpec.Category.BASE).count();
        long posts = boxes.stream().filter(b -> b.category() == BoxSpec.Category.POST).count();
        assertEquals(1, base, "one base slab");
        // (width+1) x (height+1) lattice posts.
        assertEquals(9, posts, "3x3 lattice posts for a 2x2 board");
        assertTrue(boxes.size() >= 10);
    }
}
