package com.minecart.display.preview;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;
import com.minecart.snap.SnapSceneGeometry;

import java.util.ArrayList;
import java.util.List;

/**
 * The <b>capacitor</b> preview model (1×2 footprint): a lime-green plastic body bar with a white label band
 * wrapping y=1..3 and a metallic snap-stud on top of each terminal. Built in the same unified-pixel world
 * units as {@link SnapSceneGeometry} (1 texel = 1 world unit) so it drops straight into the real renderer.
 *
 * <p><b>Shading</b> is baked into the textures via palette bias (see {@link PreviewTextures#litFace}): a
 * point's lit value shifts which of the conserved palette shades a texel is likely to pick, so light dithers
 * across the fixed palette rather than blending colours off-palette. Because the light is measured in object
 * space, a horizontal direction keeps top and side faces equally bright; adding height tilts it to a soft
 * top-lit / bottom-shadow gradient. Every face gets its own 1:1 texture so the gradient spans the whole part.
 */
final class PreviewPart implements Disposable {

    /** A directional shading setup. {@code shift} = how many palette indices the lit value swings the mean. */
    record Shading(String label, Vector3 lightDir, float shift) {}

    private static final Color BAND_WHITE = new Color(0.97f, 0.97f, 0.96f, 1f);  // bright white plastic band

    // Shading reference frame: gradient measured from the part centre. Part half-extents (X long, Y short,
    // Z medium); the gradient is normalised by the extent along the light direction, so it spans the part
    // whatever the direction — a vertical light shades over the short height, a lengthwise light over the long axis.
    private static final Vector3 SHADE_CENTER = new Vector3(0f, 2f, 0f);
    private static final float HALF_X = 12.5f, HALF_Y = 3f, HALF_Z = 4.5f;

    private final Vector3 lightDir;
    private final float shift;
    private final float shadeRadius;

    private final List<Texture> textures = new ArrayList<>();
    private final Model model;
    private final ModelInstance instance;
    private int partCounter;

    // reusable corner scratch (read immediately by litFace / rect)
    private final Vector3 q0 = new Vector3(), q1 = new Vector3(), q2 = new Vector3(), q3 = new Vector3();
    private final Vector3 nrm = new Vector3();

    private static final long ATTRS = VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal
            | VertexAttributes.Usage.TextureCoordinates;

    PreviewPart(Shading shading) {
        this.lightDir = shading.lightDir().cpy().nor();
        this.shift = shading.shift();
        this.shadeRadius = Math.max(1f,
                Math.abs(lightDir.x) * HALF_X + Math.abs(lightDir.y) * HALF_Y + Math.abs(lightDir.z) * HALF_Z);

        float body = SnapSceneGeometry.COMPONENT_FOOTPRINT;   // 9
        float bodyH = SnapSceneGeometry.COMPONENT_HEIGHT;     // 4
        float span = SnapSceneGeometry.BUMP_SPACING;          // 16 (1×2 -> one gap between two posts)
        float length = span + body;                           // 25
        float topY = bodyH;                                   // body base at y=0, top at y=4
        float studW = SnapSceneGeometry.BUMP_WIDTH;           // 3
        float studH = SnapSceneGeometry.BUMP_HEIGHT;          // 1
        float studX = span / 2f;                              // ±8: the two terminal posts
        float bandLo = 1f, bandHi = 3f;

        Color[] lime = PreviewTextures.limeWool();
        Color[] bandGray = PreviewTextures.grays(6, 0.85f, 1.0f); // whiter -> white plastic
        Color[] steel = PreviewTextures.steelBlue();              // 5-shade steel-blue metal

        ModelBuilder mb = new ModelBuilder();
        mb.begin();

        // Body: green rims [0,1] and [3,4]; white band [1,3]. All the full body footprint.
        // Body + band: shaded against the whole-part gradient (global centre, part-extent radius); random grain.
        box(mb, 0f, bandLo / 2f, 0f, length, bandLo, body, lime, Color.WHITE, false, 2, 0.3f, false, 101L, SHADE_CENTER, shadeRadius);
        box(mb, 0f, (bandHi + bodyH) / 2f, 0f, length, bodyH - bandHi, body, lime, Color.WHITE, false, 2, 0.3f, false, 202L, SHADE_CENTER, shadeRadius);
        box(mb, 0f, (bandLo + bandHi) / 2f, 0f, length, bandHi - bandLo, body, bandGray, BAND_WHITE, false, 1, 0.3f, false, 303L, SHADE_CENTER, shadeRadius);

        // Metallic snap studs: treated separately — each is shaded in its OWN local frame (centre = the stud,
        // radius = the stud's extent) so it's still lit, but the two studs come out with an IDENTICAL texture
        // (same relative geometry + same seed) regardless of the light direction.
        float studR = Math.max(1f, Math.abs(lightDir.x) * (studW / 2f)
                + Math.abs(lightDir.y) * (studH / 2f) + Math.abs(lightDir.z) * (studW / 2f));
        float studCY = topY + studH / 2f;
        // Ordered (Bayer) dither: the lit gradient interleaves shades in a fine pattern -> no clusters, no
        // bright-next-to-dark. (grainMax/zeroWeight are ignored on the ordered path.)
        box(mb, -studX, studCY, 0f, studW, studH, studW, steel, Color.WHITE, true, 1, 1.6f, true, 404L, new Vector3(-studX, studCY, 0f), studR);
        box(mb, +studX, studCY, 0f, studW, studH, studW, steel, Color.WHITE, true, 1, 1.6f, true, 404L, new Vector3(+studX, studCY, 0f), studR);

        model = mb.end();
        instance = new ModelInstance(model);
    }

    ModelInstance instance() {
        return instance;
    }

    static Vector3 center() {
        return new Vector3(SHADE_CENTER);
    }

    static float radius() {
        return (SnapSceneGeometry.BUMP_SPACING + SnapSceneGeometry.COMPONENT_FOOTPRINT) / 2f + 4f;
    }

    // --- per-face lit-palette box (1 texel == 1 world unit; shading baked into the texture) ---

    private void box(ModelBuilder mb, float cx, float cy, float cz, float sx, float sy, float sz,
                     Color[] palette, Color diffuse, boolean metallic, int grainMax, float zeroWeight,
                     boolean ordered, long seedBase, Vector3 shadeCenter, float shadeR) {
        float hx = sx / 2f, hy = sy / 2f, hz = sz / 2f;
        float x0 = cx - hx, x1 = cx + hx, y0 = cy - hy, y1 = cy + hy, z0 = cz - hz, z1 = cz + hz;
        face(mb, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, 1, 0, 0, sz, sy, palette, diffuse, metallic, grainMax, zeroWeight, ordered, seedBase + 1, shadeCenter, shadeR);
        face(mb, x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1, -1, 0, 0, sz, sy, palette, diffuse, metallic, grainMax, zeroWeight, ordered, seedBase + 2, shadeCenter, shadeR);
        face(mb, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, 0, 1, 0, sx, sz, palette, diffuse, metallic, grainMax, zeroWeight, ordered, seedBase + 3, shadeCenter, shadeR);
        face(mb, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, 0, -1, 0, sx, sz, palette, diffuse, metallic, grainMax, zeroWeight, ordered, seedBase + 4, shadeCenter, shadeR);
        face(mb, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, 0, 0, 1, sx, sy, palette, diffuse, metallic, grainMax, zeroWeight, ordered, seedBase + 5, shadeCenter, shadeR);
        face(mb, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, 0, 0, -1, sx, sy, palette, diffuse, metallic, grainMax, zeroWeight, ordered, seedBase + 6, shadeCenter, shadeR);
    }

    private void face(ModelBuilder mb,
                      float ax, float ay, float az, float bx, float by, float bz,
                      float cx, float cy, float cz, float dx, float dy, float dz,
                      float nx, float ny, float nz, float uWorld, float vWorld,
                      Color[] palette, Color diffuse, boolean metallic, int grainMax, float zeroWeight,
                      boolean ordered, long seed, Vector3 shadeCenter, float shadeR) {
        q0.set(ax, ay, az);
        q1.set(bx, by, bz);
        q2.set(cx, cy, cz);
        q3.set(dx, dy, dz);
        int pw = Math.max(1, Math.round(uWorld));
        int ph = Math.max(1, Math.round(vWorld));
        Texture tex = PreviewTextures.litFace(palette, q0, q1, q2, q3, pw, ph,
                shadeCenter, lightDir, shadeR, shift, grainMax, zeroWeight, ordered, 0x9E37_0000L + seed * 2654435761L);
        textures.add(tex);

        Material mat = new Material(
                TextureAttribute.createDiffuse(tex),
                ColorAttribute.createDiffuse(diffuse),
                IntAttribute.createCullFace(GL20.GL_NONE));
        if (metallic) {
            mat.set(ColorAttribute.createSpecular(0.70f, 0.74f, 0.82f, 1f), FloatAttribute.createShininess(45f));
        }
        MeshPartBuilder part = mb.part("f" + (partCounter++), GL20.GL_TRIANGLES, ATTRS, mat);
        part.setUVRange(0f, 0f, 1f, 1f);
        nrm.set(nx, ny, nz);
        part.rect(q0, q1, q2, q3, nrm);
    }

    @Override
    public void dispose() {
        model.dispose();
        for (Texture t : textures) {
            t.dispose();
        }
    }
}
