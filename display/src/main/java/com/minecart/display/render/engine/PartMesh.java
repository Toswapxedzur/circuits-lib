package com.minecart.display.render.engine;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes.Usage;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
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
     * An axis-aligned box. {@code cx,cy,cz} is where it sits (world, after {@link
     * ComponentInstance#collectStatic}); {@code ocx,ocy,ocz} is its ORIGINAL object-space centre; {@code paint}
     * carries the exact PreviewPart texture recipe. The object-space shading is identical for every instance.
     */
    /** White vertex tint = "no tint" (greyscale/coloured texel passes through unchanged). */
    static final float WHITE_BITS = Color.WHITE.toFloatBits();

    /** A flat TRACE decal PRINTED onto a box's top (+Y) face at bake time — the conductive line between the
     *  studs, and (for the capacitor) the two-plate symbol. Gen-time only; the runtime just loads the sprite. */
    record Trace(Color color, boolean capacitor) {}

    record Box(float cx, float cy, float cz, float sx, float sy, float sz, PaletteDither.Paint paint,
               float ocx, float ocy, float ocz, String[] faceSprites,
               boolean tint, boolean translucent, float tintBits, Trace trace) {

        /** A box whose object-space centre is its position (used at part-definition time). Sprites derived from paint. */
        static Box local(float cx, float cy, float cz, float sx, float sy, float sz, PaletteDither.Paint paint) {
            return new Box(cx, cy, cz, sx, sy, sz, paint, cx, cy, cz, null, false, false, WHITE_BITS, null);
        }

        /** A definition-time box that is tinted by the component-entity colour and/or drawn translucent. */
        static Box local(float cx, float cy, float cz, float sx, float sy, float sz, PaletteDither.Paint paint,
                         boolean tint, boolean translucent) {
            return new Box(cx, cy, cz, sx, sy, sz, paint, cx, cy, cz, null, tint, translucent, WHITE_BITS, null);
        }

        /** A definition-time box with a TRACE decal printed on its top face. */
        static Box local(float cx, float cy, float cz, float sx, float sy, float sz, PaletteDither.Paint paint,
                         Trace trace) {
            return new Box(cx, cy, cz, sx, sy, sz, paint, cx, cy, cz, null, false, false, WHITE_BITS, trace);
        }

        /** A box loaded from a model JSON: no paint, sprite name per face already resolved by the generator. */
        static Box loaded(float cx, float cy, float cz, float sx, float sy, float sz, String[] faceSprites,
                          boolean tint, boolean translucent) {
            return new Box(cx, cy, cz, sx, sy, sz, null, cx, cy, cz, faceSprites, tint, translucent, WHITE_BITS, null);
        }

        /** The atlas sprite for face {@code f}: the loaded name if present, else derived from the paint. */
        String faceSprite(int f) {
            return faceSprites != null ? faceSprites[f] : PaletteDither.faceName(this, f);
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

    /**
     * A flat, <b>oriented</b> (non-axis-aligned) quad — the engine's one non-box primitive, for a tilted plate
     * like a resistor's leads. Corners are ordered {@code p00,p10,p11,p01} (== litFace's, so U runs p00→p10, V
     * runs p00→p01); {@code o00..} are the ORIGINAL object-space corners (shading is identical for every
     * instance, so the sprite bakes once and instances). {@code pw×ph} is its sprite size in texels. Rendered
     * double-sided (both windings) so it reads from either face under {@code GL_BACK}.
     */
    record Quad(Vector3 p00, Vector3 p10, Vector3 p11, Vector3 p01,
                Vector3 o00, Vector3 o10, Vector3 o11, Vector3 o01,
                PaletteDither.Paint paint, int pw, int ph, String bakedSprite) {

        /** A quad defined in object space (object corners == world corners). Sprite derived from paint. */
        static Quad local(Vector3 p00, Vector3 p10, Vector3 p11, Vector3 p01, PaletteDither.Paint paint,
                          int pw, int ph) {
            return new Quad(p00, p10, p11, p01, p00, p10, p11, p01, paint, pw, ph, null);
        }

        /** A quad loaded from JSON: no paint, sprite name already resolved by the generator. */
        static Quad loaded(Vector3 p00, Vector3 p10, Vector3 p11, Vector3 p01, int pw, int ph, String sprite) {
            return new Quad(p00, p10, p11, p01, p00, p10, p11, p01, null, pw, ph, sprite);
        }

        /** The atlas sprite: the loaded name if present, else derived from the paint + object corners. */
        String sprite() {
            return bakedSprite != null ? bakedSprite : PaletteDither.quadName(this);
        }
    }

    private static final int FLOATS_PER_VERTEX = 3 + 2 + 1; // position, atlas uv, packed tint colour
    private static final int FLOATS_PER_INSTANCE = 16;  // a mat4 (4 vec4 columns)
    private static final float EPS = 1e-4f;

    // The face's 4 emitted corners (a,b,c,d) ARE litFace's (p00,p10,p11,p01), so their UVs are the fixed
    // (0,0),(1,0),(1,1),(0,1) — identical for every face. This makes the baked sprite line up with the geometry
    // exactly as PreviewPart's rect did (pixel-exact orientation).
    private static final float[] CORNER_UV = {0, 0, 1, 0, 1, 1, 0, 1};

    private final Mesh mesh;
    private final float[] instanceData;
    private int instanceCount;

    private PartMesh(Mesh mesh, int maxInstances) {
        this.mesh = mesh;
        this.instanceData = new float[maxInstances * FLOATS_PER_INSTANCE];
    }

    static PartMesh of(List<Box> boxes, List<Quad> quads, int maxInstances, PartAtlas atlas) {
        FloatArray v = new FloatArray();
        ShortArray idx = new ShortArray();
        for (Box b : boxes) {
            float x0 = b.min(0), x1 = b.max(0), y0 = b.min(1), y1 = b.max(1), z0 = b.min(2), z1 = b.max(2);
            // Emit a face only if it is NOT fully covered by an adjacent box (occlusion). Corners are ordered
            // CCW-from-outside (the ±X pair differs from ±Y/±Z — the shared source wound ±X inward). Each face
            // resolves its own object-space-shaded sprite ({@link PaletteDither#faceName}) and bakes its UVs.
            // A zero-thickness box (e.g. a 0-thick leg) skips its degenerate faces and emits only its two
            // coincident large faces — opposite winding = a double-sided flat quad under GL_BACK.
            float sx = b.sx(), sy = b.sy(), sz = b.sz();
            boolean fx = sy > EPS && sz > EPS, fy = sx > EPS && sz > EPS, fz = sx > EPS && sy > EPS;
            if (fx && !covered(boxes, b, 0, x1, true, y0, y1, z0, z1))
                face(v, idx, 0, atlas.region(b.faceSprite(0)), b.tintBits(),
                        x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);   // +X
            if (fx && !covered(boxes, b, 0, x0, false, y0, y1, z0, z1))
                face(v, idx, 1, atlas.region(b.faceSprite(1)), b.tintBits(),
                        x0, y0, z1, x0, y1, z1, x0, y1, z0, x0, y0, z0);   // -X
            if (fy && !covered(boxes, b, 1, y1, true, x0, x1, z0, z1))
                face(v, idx, 2, atlas.region(b.faceSprite(2)), b.tintBits(),
                        x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0);   // +Y
            if (fy && !covered(boxes, b, 1, y0, false, x0, x1, z0, z1))
                face(v, idx, 3, atlas.region(b.faceSprite(3)), b.tintBits(),
                        x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);   // -Y
            if (fz && !covered(boxes, b, 2, z1, true, x0, x1, y0, y1))
                face(v, idx, 4, atlas.region(b.faceSprite(4)), b.tintBits(),
                        x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);   // +Z
            if (fz && !covered(boxes, b, 2, z0, false, x0, x1, y0, y1))
                face(v, idx, 5, atlas.region(b.faceSprite(5)), b.tintBits(),
                        x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0);   // -Z
        }
        // Oriented quads: a flat tilted plate, DOUBLE-SIDED. Emit the 4 corners ONCE (UVs p00→p10 = U, p00→p01
        // = V) and add both triangle windings — reversing the WINDING (not the corner order) keeps each vertex's
        // UV, so the sprite isn't transposed on the back face.
        for (Quad q : quads) {
            PartAtlas.Region r = atlas.region(q.sprite());
            short base = (short) (v.size / FLOATS_PER_VERTEX);
            vertex(v, q.p00().x, q.p00().y, q.p00().z, r, CORNER_UV[0], CORNER_UV[1], WHITE_BITS);
            vertex(v, q.p10().x, q.p10().y, q.p10().z, r, CORNER_UV[2], CORNER_UV[3], WHITE_BITS);
            vertex(v, q.p11().x, q.p11().y, q.p11().z, r, CORNER_UV[4], CORNER_UV[5], WHITE_BITS);
            vertex(v, q.p01().x, q.p01().y, q.p01().z, r, CORNER_UV[6], CORNER_UV[7], WHITE_BITS);
            idx.add(base); idx.add((short) (base + 1)); idx.add((short) (base + 2));  // front
            idx.add(base); idx.add((short) (base + 2)); idx.add((short) (base + 3));
            idx.add(base); idx.add((short) (base + 2)); idx.add((short) (base + 1));  // back (reversed winding)
            idx.add(base); idx.add((short) (base + 3)); idx.add((short) (base + 2));
        }

        Mesh mesh = new Mesh(true, v.size / FLOATS_PER_VERTEX, idx.size,
                new VertexAttribute(Usage.Position, 3, "a_position"),
                new VertexAttribute(Usage.TextureCoordinates, 2, "a_uv"),
                new VertexAttribute(Usage.ColorPacked, 4, "a_color"));
        mesh.setVertices(v.items, 0, v.size);
        mesh.setIndices(idx.items, 0, idx.size);
        mesh.enableInstancedRendering(false, maxInstances,
                new VertexAttribute(Usage.Generic, 4, "i_w0"),
                new VertexAttribute(Usage.Generic, 4, "i_w1"),
                new VertexAttribute(Usage.Generic, 4, "i_w2"),
                new VertexAttribute(Usage.Generic, 4, "i_w3"));
        return new PartMesh(mesh, maxInstances);
    }

    /** Every atlas sprite the given boxes + quads can request — for pre-building the atlas. */
    static void collectSpriteNames(List<Box> boxes, List<Quad> quads, java.util.Set<String> out) {
        for (Box b : boxes) {
            for (int f = 0; f < 6; f++) {
                int[] wh = PaletteDither.size(b, f);
                if (wh[0] > 0 && wh[1] > 0) out.add(b.faceSprite(f));
            }
        }
        for (Quad q : quads) out.add(q.sprite());
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

    private static void face(FloatArray v, ShortArray idx, int faceId, PartAtlas.Region r, float tintBits,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz) {
        float[] uv = CORNER_UV; // a,b,c,d == p00,p10,p11,p01 → fixed UVs for every face
        short base = (short) (v.size / FLOATS_PER_VERTEX);
        vertex(v, ax, ay, az, r, uv[0], uv[1], tintBits);
        vertex(v, bx, by, bz, r, uv[2], uv[3], tintBits);
        vertex(v, cx, cy, cz, r, uv[4], uv[5], tintBits);
        vertex(v, dx, dy, dz, r, uv[6], uv[7], tintBits);
        idx.add(base); idx.add((short) (base + 1)); idx.add((short) (base + 2));
        idx.add(base); idx.add((short) (base + 2)); idx.add((short) (base + 3));
    }

    /** Appends one vertex: position, the corner's face UV mapped into the sprite's atlas region, packed tint. */
    private static void vertex(FloatArray v, float x, float y, float z, PartAtlas.Region r, float u, float w,
                               float tintBits) {
        v.add(x); v.add(y); v.add(z);
        v.add(r.u0() + u * (r.u1() - r.u0()));
        v.add(r.v0() + w * (r.v1() - r.v0()));
        v.add(tintBits);
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
