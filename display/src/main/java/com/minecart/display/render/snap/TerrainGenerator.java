package com.minecart.display.render.snap;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.math.Vector3;

/**
 * Procedurally generates the backdrop terrain: a seeded fBm value-noise heightfield forming rolling hills,
 * with a <b>flat clearing kept around the board</b> (so it stays grounded) that ramps up into hills
 * further out. Produces a single lit, shadow-receiving {@link Model} with height-based vertex colours
 * (grass → rock → snow) and normals from finite differences. {@link #height(float, float)} is exposed so
 * callers can plant trees on the surface.
 *
 * <p>No external dependencies — the noise is a small hash-based value noise, deterministic for a given seed.
 */
public final class TerrainGenerator {

    private static final float BASE_FREQ = 0.004f;   // ~250-unit features per octave
    private static final int OCTAVES = 4;
    private static final float MAX_HEIGHT = 260f;

    private final long seed;
    private final float centerX;
    private final float centerZ;
    private final float clearRadius;  // flat around the board
    private final float clearBlend;   // ramp width from flat to full hills

    // Optional pond: a smooth bowl carved below the surface so a flat water plane at pondSurface shows a
    // natural, terrain-clipped shoreline. Heights here are in generator space (the caller offsets the model).
    private boolean pond;
    private float pondCx, pondCz, pondRadius, pondSurface, pondDepth;

    public TerrainGenerator(long seed, float centerX, float centerZ, float clearRadius, float clearBlend) {
        this.seed = seed;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.clearRadius = clearRadius;
        this.clearBlend = clearBlend;
    }

    /** Carves a pond bowl at (cx,cz): a basin reaching {@code depth} below {@code surface} (generator space). */
    public void setPond(float cx, float cz, float radius, float surface, float depth) {
        this.pond = true;
        this.pondCx = cx;
        this.pondCz = cz;
        this.pondRadius = radius;
        this.pondSurface = surface;
        this.pondDepth = depth;
    }

    /** Terrain surface height at a world position (0 in the flat clearing, rising into hills outside it). */
    public float height(float worldX, float worldZ) {
        float dx = worldX - centerX, dz = worldZ - centerZ;
        float dist = (float) Math.sqrt(dx * dx + dz * dz);
        float falloff = smoothstep(clearRadius, clearRadius + clearBlend, dist);
        float h = (falloff <= 0f) ? 0f : fbm(worldX * BASE_FREQ, worldZ * BASE_FREQ) * MAX_HEIGHT * falloff;
        if (pond) {
            float pdx = worldX - pondCx, pdz = worldZ - pondCz;
            float pd = (float) Math.sqrt(pdx * pdx + pdz * pdz);
            float t = smoothstep(pondRadius, pondRadius * 0.5f, pd); // 0 outside → 1 well inside the pond
            if (t > 0f) {
                float basin = pondSurface - pondDepth * t; // dip below the water plane toward the middle
                h = h + (basin - h) * t;                   // blend the surrounding terrain into the bowl
            }
        }
        return h;
    }

    /**
     * Builds the terrain model: a {@code resolution × resolution} grid of size {@code size} centred on the
     * board. Vertex count stays under the 32767 short-index limit (use resolution ≤ ~180).
     */
    public Model buildModel(float size, int resolution) {
        long attrs = VertexAttributes.Usage.Position
                | VertexAttributes.Usage.Normal
                | VertexAttributes.Usage.ColorUnpacked;
        Material material = new Material(
                ColorAttribute.createDiffuse(Color.WHITE),          // white so vertex colour shows through
                IntAttribute.createCullFace(GL20.GL_NONE));         // avoid any winding pitfalls

        ModelBuilder mb = new ModelBuilder();
        mb.begin();
        MeshPartBuilder part = mb.part("terrain", GL20.GL_TRIANGLES, attrs, material);

        float half = size / 2f;
        float cell = size / resolution;
        float x0 = centerX - half, z0 = centerZ - half;

        short[][] idx = new short[resolution + 1][resolution + 1];
        MeshPartBuilder.VertexInfo vi = new MeshPartBuilder.VertexInfo();
        Vector3 normal = new Vector3();
        Color color = new Color();
        for (int i = 0; i <= resolution; i++) {
            float wx = x0 + i * cell;
            for (int j = 0; j <= resolution; j++) {
                float wz = z0 + j * cell;
                float h = height(wx, wz);
                normalAt(wx, wz, cell, normal);
                colorAt(h, color);
                vi.setPos(wx, h, wz);
                vi.setNor(normal.x, normal.y, normal.z);
                vi.setCol(color.r, color.g, color.b, 1f);
                idx[i][j] = part.vertex(vi);
            }
        }
        for (int i = 0; i < resolution; i++) {
            for (int j = 0; j < resolution; j++) {
                part.triangle(idx[i][j], idx[i + 1][j], idx[i + 1][j + 1]);
                part.triangle(idx[i][j], idx[i + 1][j + 1], idx[i][j + 1]);
            }
        }
        return mb.end();
    }

    private void normalAt(float wx, float wz, float e, Vector3 out) {
        float hl = height(wx - e, wz), hr = height(wx + e, wz);
        float hd = height(wx, wz - e), hu = height(wx, wz + e);
        out.set(hl - hr, 2f * e, hd - hu).nor();
    }

    private void colorAt(float h, Color out) {
        float t = clamp(h / MAX_HEIGHT, 0f, 1f);
        if (t < 0.45f) {
            lerp(out, 0.28f, 0.50f, 0.26f, 0.34f, 0.46f, 0.24f, t / 0.45f); // grass shades
        } else if (t < 0.72f) {
            lerp(out, 0.34f, 0.46f, 0.24f, 0.52f, 0.44f, 0.30f, (t - 0.45f) / 0.27f); // grass → rock/tan
        } else {
            lerp(out, 0.52f, 0.44f, 0.30f, 0.95f, 0.96f, 1.0f, (t - 0.72f) / 0.28f); // rock → snow
        }
    }

    private static void lerp(Color out, float r0, float g0, float b0, float r1, float g1, float b1, float t) {
        t = clamp(t, 0f, 1f);
        out.set(r0 + (r1 - r0) * t, g0 + (g1 - g0) * t, b0 + (b1 - b0) * t, 1f);
    }

    // --- value noise ---

    private float fbm(float x, float z) {
        float sum = 0f, amp = 0.5f, freq = 1f, norm = 0f;
        for (int o = 0; o < OCTAVES; o++) {
            sum += amp * valueNoise(x * freq, z * freq);
            norm += amp;
            amp *= 0.5f;
            freq *= 2f;
        }
        return sum / norm;
    }

    private float valueNoise(float x, float z) {
        int x0 = floor(x), z0 = floor(z);
        float tx = smootherstep(x - x0), tz = smootherstep(z - z0);
        float a = hash(x0, z0), b = hash(x0 + 1, z0), c = hash(x0, z0 + 1), d = hash(x0 + 1, z0 + 1);
        float ab = a + (b - a) * tx;
        float cd = c + (d - c) * tx;
        return ab + (cd - ab) * tz;
    }

    private float hash(int x, int z) {
        long h = x * 0x9E3779B97F4A7C15L + z * 0xC2B2AE3D27D4EB4FL + seed;
        h ^= (h >>> 29);
        h *= 0xBF58476D1CE4E5B9L;
        h ^= (h >>> 32);
        return (h >>> 40) / (float) (1 << 24); // [0,1)
    }

    private static int floor(float v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    private static float smootherstep(float t) {
        return t * t * (3f - 2f * t);
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = clamp((x - edge0) / (edge1 - edge0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
