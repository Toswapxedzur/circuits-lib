package com.minecart.snap;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the board→3D box mapping (no GL) against the real Snap-Circuit dimensions: a component bar is
 * centred between its two bumps at the right stack height and footprint, the base slab + base bumps are
 * emitted, and a stacked part sits one level higher.
 */
class SnapSceneGeometryTest {

    private static final float EPS = 1e-4f;
    private static final float S = SnapSceneGeometry.BUMP_SPACING; // 24 (unified to the engine pitch)

    @Test
    void horizontalBatteryBarSpansItsTwoBumps() {
        AllSnapParts.init();
        SnapPlacement p = new SnapPlacement(AllSnapParts.SNAP_BATTERY, 0, 0, 0, Facing.EAST); // (0,0)->(1,0)
        BoxSpec bar = SnapSceneGeometry.partBox(p);

        assertEquals(BoxSpec.Category.BATTERY, bar.category());
        assertEquals(S / 2f, bar.cx(), EPS);   // centred between col 0 and col 1
        assertEquals(0f, bar.cz(), EPS);
        assertEquals(SnapSceneGeometry.bodyCenterY(0), bar.cy(), EPS);
        // Runs one span + a footprint of caps along X; a footprint wide along Z; COMPONENT_HEIGHT tall.
        assertEquals(S + SnapSceneGeometry.COMPONENT_FOOTPRINT, bar.sizeX(), EPS);
        assertEquals(SnapSceneGeometry.COMPONENT_FOOTPRINT, bar.sizeZ(), EPS);
        assertEquals(SnapSceneGeometry.COMPONENT_HEIGHT, bar.sizeY(), EPS);
    }

    @Test
    void verticalPartRunsAlongZ() {
        AllSnapParts.init();
        SnapPlacement p = new SnapPlacement(AllSnapParts.SNAP_WIRE, 0, 0, 0, Facing.NORTH); // (0,0)->(0,1)
        BoxSpec bar = SnapSceneGeometry.partBox(p);
        assertEquals(SnapSceneGeometry.COMPONENT_FOOTPRINT, bar.sizeX(), EPS);
        assertEquals(S + SnapSceneGeometry.COMPONENT_FOOTPRINT, bar.sizeZ(), EPS);
        assertEquals(S / 2f, bar.cz(), EPS);
    }

    @Test
    void stackedPartSitsOneLevelHigher() {
        AllSnapParts.init();
        BoxSpec low = SnapSceneGeometry.partBox(new SnapPlacement(AllSnapParts.SNAP_WIRE, 0, 0, 0, Facing.EAST));
        BoxSpec high = SnapSceneGeometry.partBox(new SnapPlacement(AllSnapParts.SNAP_WIRE, 0, 0, 1, Facing.EAST));
        assertEquals(SnapSceneGeometry.LEVEL_HEIGHT, high.cy() - low.cy(), EPS);
    }

    @Test
    void sceneIncludesBaseSlabAndBumps() {
        SnapBoard board = new SnapBoard(2, 2, 3);
        List<BoxSpec> boxes = SnapSceneGeometry.build(board);
        long base = boxes.stream().filter(b -> b.category() == BoxSpec.Category.BASE).count();
        long bumps = boxes.stream().filter(b -> b.category() == BoxSpec.Category.BUMP).count();
        assertEquals(1, base, "one base slab");
        assertEquals(9, bumps, "(2+1)x(2+1) base bumps on an empty board");
        assertTrue(boxes.size() >= 10);
    }
}
