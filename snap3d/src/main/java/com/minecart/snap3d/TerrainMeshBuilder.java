package com.minecart.snap3d;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.jme3.util.BufferUtils;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * Dramatic, realistic terrain — NOT plain fBm (which only makes rounded bulges). Combines:
 * <ul>
 *   <li><b>domain warping</b> — sample coords are perturbed by noise, so ridges/valleys wind organically;</li>
 *   <li><b>ridged multifractal</b> (1−|noise|, squared, octave-weighted) — sharp ridgelines and jagged peaks;</li>
 *   <li>a low-frequency <b>mountain mask</b> — gates where ranges rise vs. plains;</li>
 *   <li>a gentle fBm <b>base</b> for foothills, and a flat <b>clearing</b> around the board's pier.</li>
 * </ul>
 * Rendered as a single vertex-coloured mesh (grass → rock on steep slopes → snow on peaks), with normals
 * from finite differences. {@link #height(float, float)} is exposed so callers can place props on the surface.
 */
public final class TerrainMeshBuilder {

    private static final float BASE_AMP = 90f;    // rolling foothills amplitude (world units)
    private static final float MTN_AMP = 360f;    // added mountain height where the mask allows
    private static final float BASE_FREQ = 0.0016f;
    private static final float MTN_FREQ = 0.0022f;
    private static final float MASK_FREQ = 0.0009f;
    private static final float WARP_FREQ = 0.0014f;
    private static final float WARP_AMP = 140f;    // how far the domain is warped (world units)

    private final long seed;
    private final float clearX, clearZ, clearRadius, clearBlend;

    public TerrainMeshBuilder(long seed, float clearX, float clearZ, float clearRadius, float clearBlend) {
        this.seed = seed;
        this.clearX = clearX;
        this.clearZ = clearZ;
        this.clearRadius = clearRadius;
        this.clearBlend = clearBlend;
    }

    /** World-space terrain height at (x,z): lowlands dip below the water plane, mountains tower where masked. */
    public float height(float x, float z) {
        // Domain warp: perturb the sample position by a low-freq fBm so features wind instead of blobbing.
        float wx = x + WARP_AMP * fbm(x * WARP_FREQ + 11.3f, z * WARP_FREQ + 7.1f, 4);
        float wz = z + WARP_AMP * fbm(x * WARP_FREQ - 5.2f, z * WARP_FREQ + 3.9f, 4);

        float base = fbm(wx * BASE_FREQ, wz * BASE_FREQ, 5) * BASE_AMP;         // rolling, signed
        float ridge = ridged(wx * MTN_FREQ, wz * MTN_FREQ, 6);                  // 0..~1 jagged
        float mask = smoothstep(0.45f, 0.8f, fbm(wx * MASK_FREQ, wz * MASK_FREQ, 3) * 0.5f + 0.5f);
        float full = base + ridge * MTN_AMP * mask;

        float d = (float) Math.sqrt((x - clearX) * (x - clearX) + (z - clearZ) * (z - clearZ));
        float clear = smoothstep(clearRadius, clearRadius + clearBlend, d); // 0 near the board, 1 far away
        return lerpf(-8f, full, clear); // flat shallow water around the board's pier; full terrain beyond
    }

    /** Builds the terrain mesh: {@code res × res} grid of world size {@code size}, centred on the origin. */
    public Geometry build(AssetManager assetManager, float size, int res) {
        int side = res + 1;
        int vcount = side * side;
        FloatBuffer pos = BufferUtils.createFloatBuffer(vcount * 3);
        FloatBuffer nor = BufferUtils.createFloatBuffer(vcount * 3);
        FloatBuffer col = BufferUtils.createFloatBuffer(vcount * 4);

        float half = size / 2f;
        float step = size / res;
        ColorRGBA c = new ColorRGBA();
        Vector3f n = new Vector3f();
        for (int j = 0; j <= res; j++) {
            float z = -half + j * step;
            for (int i = 0; i <= res; i++) {
                float x = -half + i * step;
                float h = height(x, z);
                normalAt(x, z, step, n);
                colorAt(h, n.y, c);
                pos.put(x).put(h).put(z);
                nor.put(n.x).put(n.y).put(n.z);
                col.put(c.r).put(c.g).put(c.b).put(1f);
            }
        }

        IntBuffer idx = BufferUtils.createIntBuffer(res * res * 6);
        for (int j = 0; j < res; j++) {
            for (int i = 0; i < res; i++) {
                int a = j * side + i, b = a + 1, cc = a + side, d = cc + 1;
                idx.put(a).put(cc).put(d);
                idx.put(a).put(d).put(b);
            }
        }

        Mesh mesh = new Mesh();
        mesh.setBuffer(VertexBuffer.Type.Position, 3, pos);
        mesh.setBuffer(VertexBuffer.Type.Normal, 3, nor);
        mesh.setBuffer(VertexBuffer.Type.Color, 4, col);
        mesh.setBuffer(VertexBuffer.Type.Index, 3, idx);
        mesh.updateBound();

        Geometry g = new Geometry("terrain", mesh);
        Material m = new Material(assetManager, "Common/MatDefs/Light/Lighting.j3md");
        m.setBoolean("UseVertexColor", true);
        m.setColor("Diffuse", ColorRGBA.White);
        m.setColor("Ambient", ColorRGBA.White);
        m.setBoolean("UseMaterialColors", true);
        m.setColor("Specular", ColorRGBA.Black);
        g.setMaterial(m);
        g.setShadowMode(RenderQueue.ShadowMode.Receive);
        return g;
    }

    private void normalAt(float x, float z, float e, Vector3f out) {
        float hl = height(x - e, z), hr = height(x + e, z);
        float hd = height(x, z - e), hu = height(x, z + e);
        out.set(hl - hr, 2f * e, hd - hu).normalizeLocal();
    }

    private void colorAt(float h, float normalY, ColorRGBA out) {
        float slope = FastMath.clamp(1f - normalY, 0f, 1f); // 0 flat, 1 vertical
        ColorRGBA grass = new ColorRGBA(0.26f, 0.44f, 0.24f, 1f);
        ColorRGBA rock = new ColorRGBA(0.40f, 0.37f, 0.34f, 1f);
        ColorRGBA snow = new ColorRGBA(0.92f, 0.94f, 1.0f, 1f);
        ColorRGBA sand = new ColorRGBA(0.62f, 0.57f, 0.42f, 1f);

        ColorRGBA base;
        if (h < 18f) {
            base = lerp(sand, grass, smoothstep(2f, 18f, h));           // shoreline
        } else if (h < 150f) {
            base = grass.clone();
        } else {
            base = lerp(grass, rock, smoothstep(150f, 240f, h));        // grass -> rock with altitude
        }
        // Steep faces are bare rock regardless of altitude.
        base = lerp(base, rock, smoothstep(0.45f, 0.75f, slope));
        // Snow caps on high, not-too-steep ground.
        if (h > 260f) {
            base = lerp(base, snow, smoothstep(260f, 340f, h) * (1f - smoothstep(0.55f, 0.8f, slope)));
        }
        out.set(base);
    }

    // --- gradient (Perlin-style) noise + fBm + ridged multifractal ---

    private float fbm(float x, float z, int octaves) {
        float sum = 0f, amp = 0.5f, freq = 1f, norm = 0f;
        for (int o = 0; o < octaves; o++) {
            sum += amp * noise(x * freq, z * freq);
            norm += amp;
            amp *= 0.5f;
            freq *= 2f;
        }
        return sum / norm; // ~[-1,1]
    }

    private float ridged(float x, float z, int octaves) {
        float sum = 0f, amp = 0.5f, freq = 1f, prev = 1f, norm = 0f;
        for (int o = 0; o < octaves; o++) {
            float n = 1f - Math.abs(noise(x * freq, z * freq));
            n *= n;                 // sharpen the ridges
            sum += n * amp * prev;  // weight by the previous octave -> crest detail
            prev = n;
            norm += amp;
            amp *= 0.5f;
            freq *= 2f;
        }
        return sum / norm; // 0..~1
    }

    private float noise(float x, float z) {
        int x0 = floor(x), z0 = floor(z);
        float fx = x - x0, fz = z - z0;
        float u = fade(fx), v = fade(fz);
        float n00 = grad(x0, z0, fx, fz);
        float n10 = grad(x0 + 1, z0, fx - 1f, fz);
        float n01 = grad(x0, z0 + 1, fx, fz - 1f);
        float n11 = grad(x0 + 1, z0 + 1, fx - 1f, fz - 1f);
        return lerpf(lerpf(n00, n10, u), lerpf(n01, n11, u), v); // ~[-1,1]
    }

    private float grad(int ix, int iz, float dx, float dz) {
        long h = ix * 0x9E3779B97F4A7C15L + iz * 0xC2B2AE3D27D4EB4FL + seed;
        h ^= (h >>> 29);
        h *= 0xBF58476D1CE4E5B9L;
        h ^= (h >>> 32);
        float ang = (h >>> 40) / (float) (1 << 24) * FastMath.TWO_PI;
        return FastMath.cos(ang) * dx + FastMath.sin(ang) * dz;
    }

    private static int floor(float v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    private static float fade(float t) {
        return t * t * t * (t * (t * 6f - 15f) + 10f);
    }

    private static float lerpf(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static ColorRGBA lerp(ColorRGBA a, ColorRGBA b, float t) {
        t = FastMath.clamp(t, 0f, 1f);
        return new ColorRGBA(a.r + (b.r - a.r) * t, a.g + (b.g - a.g) * t, a.b + (b.b - a.b) * t, 1f);
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = FastMath.clamp((x - e0) / (e1 - e0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }
}
