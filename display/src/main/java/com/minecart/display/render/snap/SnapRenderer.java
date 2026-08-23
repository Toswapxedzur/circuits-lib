package com.minecart.display.render.snap;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws a snap board in 3D with a Minecraft-like <b>unified pixel size</b>: a single pre-generated noise
 * texture is tiled so one texel is one world pixel-unit on every surface, via per-face proportional UVs.
 * The static scene is baked into one {@link Model} (rebuilt only on edit); the placement ghost reuses the
 * same proportional-UV box meshes (cached by size) with a translucent version of the item material, so it
 * reads as a genuine see-through copy of the part — including its terminal bumps.
 */
public final class SnapRenderer implements Disposable {

    /** World units covered by one full tile of the noise texture (texture is this many pixels square). */
    private static final float PIXELS_PER_TILE = 16f;
    /** How far a seated box sinks into the one below it, to keep stacked faces from being coplanar (z-fight). */
    private static final float SEAT_SINK = 0.5f;
    private static final long ATTRS = VertexAttributes.Usage.Position
            | VertexAttributes.Usage.Normal
            | VertexAttributes.Usage.TextureCoordinates;

    private final ModelBatch modelBatch = new ModelBatch();
    private final Environment environment; // shared: carries the sun + shadow map, so the board is shadowed
    private final Texture noiseTexture;
    private final EnumMap<BoxSpec.Category, Material> materials = new EnumMap<>(BoxSpec.Category.class);
    private final EnumMap<BoxSpec.Category, Material> ghostMaterials = new EnumMap<>(BoxSpec.Category.class);
    private final Material ghostInvalidMaterial;
    private final Material ghostActiveMaterial;

    private Model sceneModel;
    private ModelInstance sceneInstance;

    private final Model highlightBox;
    private final ModelInstance highlightInstance;
    private boolean highlightActive;

    // Ghost box meshes cached by size+material so the ghost gets the same proportional UVs as real parts.
    private final Map<String, Model> ghostCache = new HashMap<>();
    private final List<OrientedBox> ghostParts = new ArrayList<>();
    private boolean ghostValid;

    private final Vector3 c00 = new Vector3(), c10 = new Vector3(), c11 = new Vector3(), c01 = new Vector3();
    private final Vector3 nrm = new Vector3();

    public SnapRenderer(Environment sharedEnvironment) {
        this.environment = sharedEnvironment;

        noiseTexture = new Texture(Gdx.files.internal("textures/noise.png"), true);
        noiseTexture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        noiseTexture.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);

        for (BoxSpec.Category cat : BoxSpec.Category.values()) {
            materials.put(cat, new Material(
                    ColorAttribute.createDiffuse(colorFor(cat)),
                    TextureAttribute.createDiffuse(noiseTexture),
                    IntAttribute.createCullFace(GL20.GL_NONE)));
            ghostMaterials.put(cat, new Material(
                    ColorAttribute.createDiffuse(colorFor(cat)),
                    TextureAttribute.createDiffuse(noiseTexture),
                    new BlendingAttribute(0.55f),
                    IntAttribute.createCullFace(GL20.GL_NONE)));
        }
        ghostInvalidMaterial = new Material(
                ColorAttribute.createDiffuse(0.95f, 0.28f, 0.28f, 1f),
                TextureAttribute.createDiffuse(noiseTexture),
                new BlendingAttribute(0.55f),
                IntAttribute.createCullFace(GL20.GL_NONE));
        // The terminal currently anchored on the crosshair glows so "change terminal" is visible.
        ghostActiveMaterial = new Material(
                ColorAttribute.createDiffuse(0.2f, 0.9f, 1f, 1f),
                ColorAttribute.createEmissive(0.15f, 0.7f, 0.85f, 1f),
                new BlendingAttribute(0.8f),
                IntAttribute.createCullFace(GL20.GL_NONE));

        ModelBuilder mb = new ModelBuilder();
        Material hl = new Material(
                ColorAttribute.createDiffuse(1f, 0.9f, 0.3f, 1f),
                ColorAttribute.createEmissive(0.8f, 0.7f, 0.2f, 1f),
                new BlendingAttribute(0.35f));
        this.highlightBox = mb.createBox(1f, 1f, 1f, hl,
                VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal);
        this.highlightInstance = new ModelInstance(highlightBox);
    }

    /** Bakes the scene into a single model (one mesh part per category). Call when the board changes. */
    public void setScene(SnapScene scene) {
        if (sceneModel != null) {
            sceneModel.dispose();
            sceneModel = null;
            sceneInstance = null;
        }
        ModelBuilder mb = new ModelBuilder();
        mb.begin();
        for (BoxSpec.Category cat : BoxSpec.Category.values()) {
            List<BoxSpec> ofCat = new ArrayList<>();
            for (BoxSpec b : scene.boxes()) {
                if (b.category() == cat) {
                    ofCat.add(b);
                }
            }
            if (ofCat.isEmpty()) {
                continue;
            }
            MeshPartBuilder part = mb.part(cat.name(), GL20.GL_TRIANGLES, ATTRS, materials.get(cat));
            // Sink everything that seats on something below (bumps on the base, bodies on bumps, top bumps on
            // bodies) down by a hair so the stacked faces interpenetrate instead of being coplanar — coplanar
            // faces z-fight at any depth precision. The base slab itself seats on nothing, so it stays put.
            float sink = (cat == BoxSpec.Category.BASE) ? 0f : SEAT_SINK;
            for (BoxSpec b : ofCat) {
                addBox(part, b.cx(), b.cy() - sink / 2f, b.cz(), b.sizeX(), b.sizeY() + sink, b.sizeZ());
            }
        }
        sceneModel = mb.end();
        sceneInstance = new ModelInstance(sceneModel);
    }

    /** The board mesh, for the shadow depth pass (so the board casts shadows). Null before the first scene. */
    public ModelInstance shadowCaster() {
        return sceneInstance;
    }

    /** Sets the ghost geometry (rotating body bar + terminal bumps) and whether the placement is valid. */
    public void setGhost(List<OrientedBox> boxes, boolean valid) {
        ghostParts.clear();
        if (boxes != null) {
            ghostParts.addAll(boxes);
        }
        ghostValid = valid;
    }

    /** Sets (or clears with {@code null}) the box currently highlighted by the crosshair. */
    public void setHighlight(BoxSpec box) {
        highlightActive = box != null;
        if (highlightActive) {
            highlightInstance.transform.setToTranslationAndScaling(
                    box.cx(), box.cy(), box.cz(),
                    box.sizeX() * 1.08f, box.sizeY() * 1.08f, box.sizeZ() * 1.08f);
        }
    }

    /** Draws the scene, hover highlight, and placement ghost from {@code camera}'s viewpoint. */
    public void render(Camera camera) {
        modelBatch.begin(camera);
        if (sceneInstance != null) {
            modelBatch.render(sceneInstance, environment);
        }
        if (highlightActive) {
            modelBatch.render(highlightInstance, environment);
        }
        for (OrientedBox g : ghostParts) {
            Model mesh = ghostMesh(g.sizeX(), g.sizeY(), g.sizeZ(), g.category(), g.active());
            ModelInstance inst = new ModelInstance(mesh);
            inst.transform.setToTranslation(g.cx(), g.cy(), g.cz());
            if (g.yawDeg() != 0f) {
                inst.transform.rotate(Vector3.Y, -g.yawDeg()); // align mesh +X with the heading in XZ
            }
            modelBatch.render(inst, environment);
        }
        modelBatch.end();
    }

    /** A cached, origin-centred, proportional-UV box mesh of the given size + ghost material. */
    private Model ghostMesh(float sizeX, float sizeY, float sizeZ, BoxSpec.Category category, boolean active) {
        int sx = Math.round(sizeX), sy = Math.round(sizeY), sz = Math.round(sizeZ);
        String matKey = active ? "ACTIVE" : (ghostValid ? category.name() : "INVALID");
        String key = sx + "_" + sy + "_" + sz + "_" + matKey;
        Model cached = ghostCache.get(key);
        if (cached == null) {
            Material mat = active ? ghostActiveMaterial : (ghostValid ? ghostMaterials.get(category) : ghostInvalidMaterial);
            ModelBuilder mb = new ModelBuilder();
            mb.begin();
            MeshPartBuilder part = mb.part("ghost", GL20.GL_TRIANGLES, ATTRS, mat);
            addBox(part, 0f, 0f, 0f, sizeX, sizeY, sizeZ);
            cached = mb.end();
            ghostCache.put(key, cached);
        }
        return cached;
    }

    private void addBox(MeshPartBuilder part, float cx, float cy, float cz, float sizeX, float sizeY, float sizeZ) {
        float hx = sizeX / 2f, hy = sizeY / 2f, hz = sizeZ / 2f;
        float x0 = cx - hx, x1 = cx + hx, y0 = cy - hy, y1 = cy + hy, z0 = cz - hz, z1 = cz + hz;
        face(part, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, 1, 0, 0, sizeZ, sizeY);
        face(part, x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1, -1, 0, 0, sizeZ, sizeY);
        face(part, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, 0, 1, 0, sizeX, sizeZ);
        face(part, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, 0, -1, 0, sizeX, sizeZ);
        face(part, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, 0, 0, 1, sizeX, sizeY);
        face(part, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, 0, 0, -1, sizeX, sizeY);
    }

    private void face(MeshPartBuilder part,
                      float ax, float ay, float az, float bx, float by, float bz,
                      float cx, float cy, float cz, float dx, float dy, float dz,
                      float nx, float ny, float nz, float uWorld, float vWorld) {
        c00.set(ax, ay, az);
        c10.set(bx, by, bz);
        c11.set(cx, cy, cz);
        c01.set(dx, dy, dz);
        nrm.set(nx, ny, nz);
        part.setUVRange(0f, 0f, uWorld / PIXELS_PER_TILE, vWorld / PIXELS_PER_TILE);
        part.rect(c00, c10, c11, c01, nrm);
    }

    private static Color colorFor(BoxSpec.Category category) {
        return switch (category) {
            case BASE -> new Color(0.16f, 0.18f, 0.22f, 1f);
            case BUMP -> new Color(0.62f, 0.64f, 0.68f, 1f);
            case WIRE -> new Color(0.85f, 0.86f, 0.90f, 1f);
            case RESISTOR -> new Color(0.86f, 0.52f, 0.20f, 1f);
            case BATTERY -> new Color(0.28f, 0.72f, 0.38f, 1f);
            case UNKNOWN -> Color.MAGENTA;
        };
    }

    @Override
    public void dispose() {
        modelBatch.dispose();
        if (sceneModel != null) {
            sceneModel.dispose();
        }
        highlightBox.dispose();
        for (Model m : ghostCache.values()) {
            m.dispose();
        }
        ghostCache.clear();
        noiseTexture.dispose();
    }
}
