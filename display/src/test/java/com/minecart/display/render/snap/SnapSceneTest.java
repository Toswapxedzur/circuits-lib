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
 * Verifies ray-pick geometry (no GL): each placed component yields one pickable bounding box (base + bumps
 * aren't pickable), and a ray onto the bar hits while a ray aimed away misses.
 */
class SnapSceneTest {

    @Test
    void onePickableBoundingBoxPerComponent() {
        AllSnapParts.init();
        SnapBoard board = new SnapBoard(4, 4, 3);
        board.place(AllSnapParts.SNAP_BATTERY, 0, 0, 0, Facing.EAST, 2.0);
        board.place(AllSnapParts.SNAP_RESISTOR, 0, 0, 1, Facing.EAST, 1.0); // stacked

        SnapScene scene = SnapScene.of(board);
        assertEquals(2, scene.pickables().size(), "one pickable per component, not per bump");
    }

    @Test
    void rayOntoBarHitsAndRayAsideMisses() {
        AllSnapParts.init();
        SnapBoard board = new SnapBoard(4, 4, 3);
        board.place(AllSnapParts.SNAP_RESISTOR, 0, 0, 0, Facing.EAST, 1.0);

        BoundingBox bounds = SnapScene.of(board).pickables().get(0).bounds();
        Vector3 out = new Vector3();

        // Straight down onto the bar's centre (between bumps (0,0) and (1,0)).
        float midX = SnapSceneGeometry.BUMP_SPACING / 2f;
        Ray down = new Ray(new Vector3(midX, 100f, 0f), new Vector3(0f, -1f, 0f));
        assertTrue(Intersector.intersectRayBounds(down, bounds, out), "a ray onto the bar should hit");

        Ray away = new Ray(new Vector3(1000f, 100f, 1000f), new Vector3(0f, 1f, 0f));
        assertFalse(Intersector.intersectRayBounds(away, bounds, out), "a ray aimed away should miss");
    }
}
