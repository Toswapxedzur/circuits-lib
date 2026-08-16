package com.minecart.snap;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The direction set is length-based and NOT limited to orthogonal: length 1 gives the 4 axes, length 5
 * additionally gives the 3-4-5 diagonals.
 */
class SnapDirectionsTest {

    private static boolean has(List<int[]> dirs, int dc, int dr) {
        return dirs.stream().anyMatch(d -> d[0] == dc && d[1] == dr);
    }

    @Test
    void lengthOneIsTheFourOrthogonalDirections() {
        List<int[]> d = SnapDirections.forLength(1);
        assertEquals(4, d.size());
        assertTrue(has(d, 1, 0) && has(d, -1, 0) && has(d, 0, 1) && has(d, 0, -1));
    }

    @Test
    void lengthFiveIncludesThe345Diagonals() {
        List<int[]> d = SnapDirections.forLength(5);
        // (5,0),(0,5),(3,4),(4,3) and all sign variants = 12 directions.
        assertEquals(12, d.size());
        assertTrue(has(d, 5, 0) && has(d, 0, 5) && has(d, 3, 4) && has(d, 4, 3));
        assertTrue(has(d, -3, 4) && has(d, 3, -4) && has(d, -4, -3));
        // Every direction has Euclidean length exactly 5.
        assertTrue(d.stream().allMatch(v -> v[0] * v[0] + v[1] * v[1] == 25));
    }
}
