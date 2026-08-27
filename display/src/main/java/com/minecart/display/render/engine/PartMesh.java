package com.minecart.display.render.engine;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes.Usage;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.ShortArray;

import java.util.List;

/**
 * The base mesh for one part-type: a set of axis-aligned coloured boxes baked into one {@link Mesh} with GPU
 * instancing enabled. Each face samples its own atlas sprite (per-face art, Minecraft-style); the atlas UVs
 * are <b>baked into the vertices at build time</b> (a face maps its sprite region 0..1), so the shader is a
 * pure texture sampler — no runtime lighting or dither. Two face-savings at bake time:
 * <ul>
 *   <li><b>Occlusion cull</b> — a face fully covered by an adjacent solid box in the same part is dropped.</li>
 *   <li><b>Back-face cull</b> — faces wind CCW-from-outside, so {@code GL_BACK} keeps only the outer face at
 *       any shared plane (winding-based, so no per-vertex normal is needed).</li>
 * </ul>
 * Every frame the renderer pushes all live instance transforms and issues ONE instanced draw call.
 */
final class PartMesh implements Disposable {

    /**
     * An axis-aligned coloured box. {@code cx,cy,cz} is where it sits (world, after {@link
     * ComponentInstance#collectStatic}); {@code ocx,ocy,ocz} is its ORIGINAL object-space centre, kept so the
     * baked object-space shading gradient ({@link PaletteDither}) is identical for every instance.
     */
    record Box(float cx, float cy, float cz, float sx, float sy, float sz, Color color,
               float ocx, float ocy, float ocz) {

        /** A box whose object-space centre is its position (used at part-definition time). */
        static Box local(float cx, float cy, float cz, float sx, float sy, float sz, Color color) {
            return new Box(cx, cy, cz, sx, sy, sz, color, cx, cy, cz);
        }

        float min(int axis) {
            return center(axis) - size(axis) / 2f;
        }

        float max(int axis) {
            return center(axis) + size(axis) / 2f;
        }

        private float center(int axis) {
            return axis == 0 ? cx : axis == 1 ? cy : cz;
        }

        private float size(int axis) {
            return axis == 0 ? sx : axis == 1 ? sy : sz;
        }
    }

    private static final int FLOATS_PER_VERTEX = 3 + 2; // position, atlas uv
    private static final int FLOATS_PER_INSTANCE = 16;  // a mat4 (4 vec4 columns)
    private static final float EPS = 1e-4f;

    // Per-face corner UVs in [0,1] (matching the corner order emitted below), authored "seen from outside,
    // U→right, V→down". Index: 0 +X, 1 -X, 2 +Y, 3 -Y, 4 +Z, 5 -Z.
    private static final float[][] FACE_UV = {
            {0, 1, 0, 0, 1, 0, 1, 1}, // +X
            {0, 1, 0, 0, 1, 0, 1, 1}, // -X
            {0, 1, 1, 1, 1, 0, 0, 0}, // +Y
            {0, 0, 1, 0, 1, 1, 0, 1}, // -Y
            {0, 1, 1, 1, 1, 0, 0, 0}, // +Z
            {0, 1, 1, 1, 1, 0, 0, 0}, // -Z
    };

    private final Mesh mesh;
    private final float[] instanceData;
    private int instanceCount;

    private PartMesh(Mesh mesh, int maxInstances) {
        this.mesh = mesh;
        this.instanceData = new float[maxInstances * FLOATS_PER_INSTANCE];
    }

    static PartMesh of(List<Box> boxes, int maxInstances, PartAtlas atlas) {
        FloatArray v = new FloatArray();
        ShortArray idx = new ShortArray();
        for (Box b : boxes) {
            float x0 = b.min(0), x1 = b.max(0), y0 = b.min(1), y1 = b.max(1), z0 = b.min(2), z1 = b.max(2);
            // Emit a face only if it is NOT fully covered by an adjacent box (occlusion). Corners are ordered
            // CCW-from-outside (the ±X pair differs from ±Y/±Z — the shared source wound ±X inward). Each face
            // resolves its own object-space-shaded sprite ({@link PaletteDither#faceName}) and bakes its UVs.
            if (!covered(boxes, b, 0, x1, true, y0, y1, z0, z1))
                face(v, idx, 0, atlas.region(PaletteDither.faceName(b, 0)),
                        x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);   // +X
            if (!covered(boxes, b, 0, x0, false, y0, y1, z0, z1))
                face(v, idx, 1, atlas.region(PaletteDither.faceName(b, 1)),
                        x0, y0, z1, x0, y1, z1, x0, y1, z0, x0, y0, z0);   // -X
            if (!covered(boxes, b, 1, y1, true, x0, x1, z0, z1))
                face(v, idx, 2, atlas.region(PaletteDither.faceName(b, 2)),
                        x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0);   // +Y
            if (!covered(boxes, b, 1, y0, false, x0, x1, z0, z1))
                face(v, idx, 3, atlas.region(PaletteDither.faceName(b, 3)),
                        x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);   // -Y
            if (!covered(boxes, b, 2, z1, true, x0, x1, y0, y1))
                face(v, idx, 4, atlas.region(PaletteDither.faceName(b, 4)),
                        x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);   // +Z
            if (!covered(boxes, b, 2, z0, false, x0, x1, y0, y1))
                face(v, idx, 5, atlas.region(PaletteDither.faceName(b, 5)),
                        x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0);   // -Z
        }

        Mesh mesh = new Mesh(true, v.size / FLOATS_PER_VERTEX, idx.size,
                new VertexAttribute(Usage.Position, 3, "a_position"),
                new VertexAttribute(Usage.TextureCoordinates, 2, "a_uv"));
        mesh.setVertices(v.items, 0, v.size);
        mesh.setIndices(idx.items, 0, idx.size);
        mesh.enableInstancedRendering(false, maxInstances,
                new VertexAttribute(Usage.Generic, 4, "i_w0"),
                new VertexAttribute(Usage.Generic, 4, "i_w1"),
                new VertexAttribute(Usage.Generic, 4, "i_w2"),
                new VertexAttribute(Usage.Generic, 4, "i_w3"));
        return new PartMesh(mesh, maxInstances);
    }

    /**
     * True if {@code self}'s face — on {@code axis} at {@code coord}, spanning the two other axes over
     * [{@code a0},{@code a1}]×[{@code b0},{@code b1}] — is fully covered by an adjacent box abutting on the
     * outside ({@code positive} = the +axis side). Conservative: only a single fully-covering box hides it.
     */
    private static boolean covered(List<Box> boxes, Box self, int axis, float coord, boolean positive,
                                   float a0, float a1, float b0, float b1) {
        int ax = axis == 0 ? 1 : 0;
        int bx = axis == 2 ? 1 : 2;
        for (Box o : boxes) {
            if (o == self) continue;
            float oFace = positive ? o.min(axis) : o.max(axis);
            if (Math.abs(oFace - coord) > EPS) continue;
            if (o.min(ax) <= a0 + EPS && o.max(ax) >= a1 - EPS && o.min(bx) <= b0 + EPS && o.max(bx) >= b1 - EPS) {
                return true;
            }
        }
        return false;
    }

    private static void face(FloatArray v, ShortArray idx, int faceId, PartAtlas.Region r,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz) {
        float[] uv = FACE_UV[faceId];
        short base = (short) (v.size / FLOATS_PER_VERTEX);
        vertex(v, ax, ay, az, r, uv[0], uv[1]);
        vertex(v, bx, by, bz, r, uv[2], uv[3]);
        vertex(v, cx, cy, cz, r, uv[4], uv[5]);
        vertex(v, dx, dy, dz, r, uv[6], uv[7]);
        idx.add(base); idx.add((short) (base + 1)); idx.add((short) (base + 2));
        idx.add(base); idx.add((short) (base + 2)); idx.add((short) (base + 3));
    }

    /** Appends one vertex; maps the corner's face UV (0..1) into the sprite's atlas region. */
    private static void vertex(FloatArray v, float x, float y, float z, PartAtlas.Region r, float u, float w) {
        v.add(x); v.add(y); v.add(z);
        v.add(r.u0() + u * (r.u1() - r.u0()));
        v.add(r.v0() + w * (r.v1() - r.v0()));
    }

    void begin() {
        instanceCount = 0;
    }

    void add(Matrix4 world) {
        System.arraycopy(world.val, 0, instanceData, instanceCount * FLOATS_PER_INSTANCE, FLOATS_PER_INSTANCE);
        instanceCount++;
    }

    void render(ShaderProgram shader) {
        if (instanceCount == 0) {
            return;
        }
        mesh.setInstanceData(instanceData, 0, instanceCount * FLOATS_PER_INSTANCE);
        mesh.render(shader, GL20.GL_TRIANGLES);
    }

    @Override
    public void dispose() {
        mesh.dispose();
    }
}
