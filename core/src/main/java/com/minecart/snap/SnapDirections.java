package com.minecart.snap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The acceptable placement directions for a part of a given length — deliberately <b>not</b> limited to
 * the four orthogonal axes. A length-{@code L} part accepts every integer offset {@code (dCol,dRow)} whose
 * Euclidean length is exactly {@code L} (i.e. {@code dCol²+dRow² = L²}), so:
 * <ul>
 *   <li>length 1 → the 4 orthogonal directions;</li>
 *   <li>length 5 → additionally the diagonal 3-4-5 directions {@code (±3,±4)} and {@code (±4,±3)}.</li>
 * </ul>
 * Directions are returned sorted by angle so cycling them with the scroll wheel sweeps smoothly around.
 */
public final class SnapDirections {

    private SnapDirections() {}

    /** All non-zero integer offsets whose length is exactly {@code length}, ordered by angle. */
    public static List<int[]> forLength(int length) {
        List<int[]> dirs = new ArrayList<>();
        int target = length * length;
        for (int dc = -length; dc <= length; dc++) {
            for (int dr = -length; dr <= length; dr++) {
                if ((dc != 0 || dr != 0) && dc * dc + dr * dr == target) {
                    dirs.add(new int[]{dc, dr});
                }
            }
        }
        dirs.sort(Comparator.comparingDouble(d -> Math.atan2(d[1], d[0])));
        return dirs;
    }
}
