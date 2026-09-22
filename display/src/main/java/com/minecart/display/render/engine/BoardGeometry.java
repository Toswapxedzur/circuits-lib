package com.minecart.display.render.engine;

import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.math.collision.Ray;

import java.util.ArrayList;
import java.util.List;

/**
 * Stateless box/AABB/silhouette geometry for the physical board — the pure math that {@link PhysicalBoardView}'s
 * picking and outline drawing stand on, extracted so the board keeps only placement state + presentation (SRP).
 * All world AABBs are {@code [minX,minY,minZ, maxX,maxY,maxZ]}; parts only ever use 90° yaws so their boxes stay
 * axis-aligned under any placement transform.
 */
final class BoardGeometry {
    private BoardGeometry() {}

    private static final float EDGE_EPS = 0.05f;   // probe distance for the "face exposed here?" crease test

    /** World AABB of one model box under {@code world} (boxes stay axis-aligned under the 90° yaws parts use). */
    static float[] boxWorldAabb(PartMesh.Box b, Matrix4 world) {
        Vector3 t = new Vector3();
        float minx = Float.MAX_VALUE, miny = minx, minz = minx, maxx = -minx, maxy = -minx, maxz = -minx;
        for (int c = 0; c < 8; c++) {
            t.set(b.cx() + ((c & 1) == 0 ? -b.sx() : b.sx()) / 2f,
                    b.cy() + ((c & 2) == 0 ? -b.sy() : b.sy()) / 2f,
                    b.cz() + ((c & 4) == 0 ? -b.sz() : b.sz()) / 2f).mul(world);
            minx = Math.min(minx, t.x); maxx = Math.max(maxx, t.x);
            miny = Math.min(miny, t.y); maxy = Math.max(maxy, t.y);
            minz = Math.min(minz, t.z); maxz = Math.max(maxz, t.z);
        }
        return new float[]{minx, miny, minz, maxx, maxy, maxz};
    }

    /** World AABB of a {@link ComponentModel.Collision} half-extent box under {@code world}. */
    static float[] collisionWorldAabb(ComponentModel.Collision c, Matrix4 world) {
        Vector3 t = new Vector3();
        float minx = Float.MAX_VALUE, miny = minx, minz = minx, maxx = -minx, maxy = -minx, maxz = -minx;
        for (int i = 0; i < 8; i++) {
            t.set(c.cx() + ((i & 1) == 0 ? -c.hx() : c.hx()),
                    c.cy() + ((i & 2) == 0 ? -c.hy() : c.hy()),
                    c.cz() + ((i & 4) == 0 ? -c.hz() : c.hz())).mul(world);
            minx = Math.min(minx, t.x); maxx = Math.max(maxx, t.x);
            miny = Math.min(miny, t.y); maxy = Math.max(maxy, t.y);
            minz = Math.min(minz, t.z); maxz = Math.max(maxz, t.z);
        }
        return new float[]{minx, miny, minz, maxx, maxy, maxz};
    }

    /** Ray vs world AABB (min/max flat array); writes the hit point into {@code out} and returns whether it hit. */
    static boolean rayHitsAabb(Ray ray, float[] ab, Vector3 out) {
        return Intersector.intersectRayBounds(ray,
                new BoundingBox(new Vector3(ab[0], ab[1], ab[2]), new Vector3(ab[3], ab[4], ab[5])), out);
    }

    /** Ray/AABB slab test: the ray parameter where {@code eye + t·dir} ENTERS {@code box}, or +∞ if it misses. */
    static float rayBoxEntry(Vector3 eye, Vector3 dir, float[] box) {
        float tmin = -Float.MAX_VALUE, tmax = Float.MAX_VALUE;
        for (int ax = 0; ax < 3; ax++) {
            float o = ax == 0 ? eye.x : ax == 1 ? eye.y : eye.z;
            float dd = ax == 0 ? dir.x : ax == 1 ? dir.y : dir.z;
            float lo = box[ax], hi = box[ax + 3];
            if (Math.abs(dd) < 1e-9f) {
                if (o < lo || o > hi) return Float.MAX_VALUE;
            } else {
                float t1 = (lo - o) / dd, t2 = (hi - o) / dd;
                if (t1 > t2) { float t = t1; t1 = t2; t2 = t; }
                tmin = Math.max(tmin, t1); tmax = Math.min(tmax, t2);
                if (tmin > tmax) return Float.MAX_VALUE;
            }
        }
        return tmax < 0 ? Float.MAX_VALUE : tmin;
    }

    /** True if point (x,y,z) is inside ANY of the object-space boxes (used by the crease-edge exposure test). */
    static boolean insideAny(List<PartMesh.Box> boxes, float x, float y, float z) {
        for (PartMesh.Box b : boxes) {
            if (Math.abs(x - b.cx()) <= b.sx() / 2f && Math.abs(y - b.cy()) <= b.sy() / 2f && Math.abs(z - b.cz()) <= b.sz() / 2f) {
                return true;
            }
        }
        return false;
    }

    /**
     * Object-space CREASE edges of the union of {@code boxes}: a flat array of segments (x0,y0,z0,x1,y1,z1)*N.
     * An edge is kept iff both faces meeting at it are exposed there (a sample just outside each face lies in no
     * box) — flush seams between stacked/adjacent boxes vanish; plate, stud, dome and knob silhouettes remain.
     */
    static float[] shapeEdges(List<PartMesh.Box> boxes) {
        List<Float> out = new ArrayList<>();
        for (PartMesh.Box b : boxes) {
            float hx = b.sx() / 2f, hy = b.sy() / 2f, hz = b.sz() / 2f;
            // 12 edges: along X at (±y,±z), along Y at (±x,±z), along Z at (±x,±y); the two adjacent face normals
            // are the signs of the two fixed coordinates.
            for (int axis = 0; axis < 3; axis++) {
                for (int s1 = -1; s1 <= 1; s1 += 2) {
                    for (int s2 = -1; s2 <= 1; s2 += 2) {
                        float[] p0 = new float[3], p1 = new float[3], mid = new float[3], n1 = new float[3], n2 = new float[3];
                        int a1 = (axis + 1) % 3, a2 = (axis + 2) % 3;
                        float[] c = {b.cx(), b.cy(), b.cz()}, h = {hx, hy, hz};
                        p0[axis] = c[axis] - h[axis]; p1[axis] = c[axis] + h[axis];
                        p0[a1] = p1[a1] = c[a1] + s1 * h[a1];
                        p0[a2] = p1[a2] = c[a2] + s2 * h[a2];
                        for (int k = 0; k < 3; k++) mid[k] = (p0[k] + p1[k]) / 2f;
                        n1[a1] = s1; n2[a2] = s2;
                        boolean f1 = !insideAny(boxes, mid[0] + n1[0] * EDGE_EPS, mid[1] + n1[1] * EDGE_EPS, mid[2] + n1[2] * EDGE_EPS);
                        boolean f2 = !insideAny(boxes, mid[0] + n2[0] * EDGE_EPS, mid[1] + n2[1] * EDGE_EPS, mid[2] + n2[2] * EDGE_EPS);
                        if (f1 && f2) { for (float v : p0) out.add(v); for (float v : p1) out.add(v); }
                    }
                }
            }
        }
        float[] r = new float[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    /** Placement-clash test between two world AABBs: touching faces on x/z are allowed, and a ~1u under-stud peg
     *  interlock in Y is treated as a connection, not a clash. */
    static boolean overlap(float[] a, float[] b) {
        float eps = 0.5f;    // x/z: touching faces allowed
        float epsY = 1.5f;   // y: the under-stud PEG interlock at a stacked joint (~1u) is a connection, not a clash
        return a[0] < b[3] - eps && a[3] > b[0] + eps
                && a[1] < b[4] - epsY && a[4] > b[1] + epsY
                && a[2] < b[5] - eps && a[5] > b[2] + eps;
    }
}
