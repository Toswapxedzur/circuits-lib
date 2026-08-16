package com.minecart.display.render.snap;

import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.math.collision.Ray;
import com.minecart.snap.AllSnapParts;
import com.minecart.snap.Facing;
import com.minecart.snap.SnapBoard;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies ray-pick geometry (no GL): each placed part yields one pickable bounding box, and a ray cast
 * down onto a part hits it while a ray aimed away misses. Uses libGDX math only (BoundingBox / Intersector
 * / Ray), so it runs headless.
 */
class SnapSceneTest {

    @Test
    void oneBoundingBoxPerPartAndItSpansTheBar() {
        AllSnapParts.init();
        SnapBoard board = new SnapBoard(1, 1, 1);
        board.place(AllSnapParts.SNAP_BATTERY, 0, 0, 0, Facing.EAST, 2.0); // bar centred at (8, 2, 0)

        SnapScene scene = SnapScene.of(board);
        assertEquals(1, scene.pickables().size(), "one pickable per placed part");

        BoundingBox bounds = scene.pickables().get(0).bounds();
        float cell = SnapSceneGeometry.CELL;
        // Bar spans a full cell along X, centred at x=cell/2, so it reaches x=0..cell.
        assertEquals(0f, bounds.min.x, 1e-4f);
        assertEquals(cell, bounds.max.x, 1e-4f);
        assertEquals(0f, bounds.min.y, 1e-4f);
        assertEquals(SnapSceneGeometry.PART_HEIGHT, bounds.max.y, 1e-4f);
    }

    @Test
    void rayFromAboveHitsThePartAndRayAsideMisses() {
        AllSnapParts.init();
        SnapBoard board = new SnapBoard(1, 1, 1);
        board.place(AllSnapParts.SNAP_RESISTOR, 0, 0, 0, Facing.EAST, 1.0);

        BoundingBox bounds = SnapScene.of(board).pickables().get(0).bounds();
        Vector3 out = new Vector3();

        // Straight down onto the bar's centre.
        Ray down = new Ray(new Vector3(SnapSceneGeometry.CELL / 2f, 100f, 0f), new Vector3(0f, -1f, 0f));
        assertTrue(Intersector.intersectRayBounds(down, bounds, out), "a ray onto the bar should hit");

        // Pointing away from the board entirely.
        Ray away = new Ray(new Vector3(1000f, 100f, 1000f), new Vector3(0f, 1f, 0f));
        assertFalse(Intersector.intersectRayBounds(away, bounds, out), "a ray aimed away should miss");
    }
}
