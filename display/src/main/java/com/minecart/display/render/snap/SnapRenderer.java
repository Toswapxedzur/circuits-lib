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
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/**
 * Draws a snap board in 3D with a Minecraft-like <b>unified pixel size</b>: a single pre-generated noise
 * texture is tiled so that one texel is exactly one world pixel-unit on every surface, regardless of a
 * box's size. The scene is baked into one {@link Model} (rebuilt only on edit) with a mesh part per
 * {@link BoxSpec.Category} colour; each face gets UVs proportional to its world dimensions
 * ({@code size / PIXELS_PER_TILE}) with the texture wrapped Repeat + Nearest. Back-face culling is off and
 * each face carries an explicit outward normal, so winding never matters for visibility or lighting.
 *
 * <p>Translucent hover-highlight and placement-ghost cubes are drawn on top as simple scaled unit boxes.
 */
public final class SnapRenderer implements Disposable {

    /** World units covered by one full tile of the noise texture (texture is this many pixels square). */
    private static final float PIXELS_PER_TILE = 16f;

    private final ModelBatch modelBatch = new ModelBatch();
    private final Environment environment = new Environment();
    private final Texture noiseTexture;
    private final EnumMap<BoxSpec.Category, Material> materials = new EnumMap<>(BoxSpec.Category.class);

    private Model sceneModel;
    private ModelInstance sceneInstance;

    private final Model highlightBox;
    private final ModelInstance highlightInstance;
    private boolean highlightActive;

    private final EnumMap<BoxSpec.Category, Model> ghostModels = new EnumMap<>(BoxSpec.Category.class);
    private final EnumMap<BoxSpec.Category, ModelInstance> ghostInstances = new EnumMap<>(BoxSpec.Category.class);
    private final Model ghostInvalidBox;
    private final ModelInstance ghostInvalidInstance;
    private BoxSpec ghostBox;
    private boolean ghostValid;

    // Reused corner/normal temporaries for face building.
    private final Vector3 c00 = new Vector3(), c10 = new Vector3(), c11 = new Vector3(), c01 = new Vector3();
    private final Vector3 nrm = new Vector3();

    public SnapRenderer() {
        environment.set(new ColorAttribute(ColorAttribute.AmbientLight, 0.5f, 0.5f, 0.55f, 1f));
        DirectionalLight sun = new DirectionalLight();
        sun.set(0.95f, 0.95f, 0.9f, -0.45f, -1f, -0.65f);
        environment.add(sun);

        noiseTexture = new Texture(Gdx.files.internal("textures/noise.png"), true);
        noiseTexture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        noiseTexture.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);

        for (BoxSpec.Category cat : BoxSpec.Category.values()) {
            materials.put(cat, new Material(
                    ColorAttribute.createDiffuse(colorFor(cat)),
                    TextureAttribute.createDiffuse(noiseTexture),
                    IntAttribute.createCullFace(GL20.GL_NONE)));
        }

        ModelBuilder mb = new ModelBuilder();
        Material hl = new Material(
                ColorAttribute.createDiffuse(1f, 0.9f, 0.3f, 1f),
                ColorAttribute.createEmissive(0.8f, 0.7f, 0.2f, 1f),
                new BlendingAttribute(0.35f));
        long overlayAttrs = VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal;
        this.highlightBox = mb.createBox(1f, 1f, 1f, hl, overlayAttrs);
        this.highlightInstance = new ModelInstance(highlightBox);

        // A valid ghost is a transparent version of the ACTUAL item: same noise texture + colour, lit
        // (not emissive), at half opacity — so it reads as a faded copy rather than a glowing block.
        long ghostAttrs = VertexAttributes.Usage.Position
                | VertexAttributes.Usage.Normal
                | VertexAttributes.Usage.TextureCoordinates;
        for (BoxSpec.Category cat : BoxSpec.Category.values()) {
            Material ghost = new Material(
                    ColorAttribute.createDiffuse(colorFor(cat)),
                    TextureAttribute.createDiffuse(noiseTexture),
                    new BlendingAttribute(0.5f),
                    IntAttribute.createCullFace(GL20.GL_NONE));
            Model model = mb.createBox(1f, 1f, 1f, ghost, ghostAttrs);
            ghostModels.put(cat, model);
            ghostInstances.put(cat, new ModelInstance(model));
        }
        Material invalid = new Material(
                ColorAttribute.createDiffuse(0.95f, 0.25f, 0.25f, 1f),
                TextureAttribute.createDiffuse(noiseTexture),
                new BlendingAttribute(0.5f),
                IntAttribute.createCullFace(GL20.GL_NONE));
        this.ghostInvalidBox = mb.createBox(1f, 1f, 1f, invalid, ghostAttrs);
        this.ghostInvalidInstance = new ModelInstance(ghostInvalidBox);
    }

    /** Bakes the scene into a single model (one mesh part per category). Call when the board changes. */
    public void setScene(SnapScene scene) {
        if (sceneModel != null) {
            sceneModel.dispose();
            sceneModel = null;
            sceneInstance = null;
        }
        long attrs = VertexAttributes.Usage.Position
                | VertexAttributes.Usage.Normal
                | VertexAttributes.Usage.TextureCoordinates;

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
            MeshPartBuilder part = mb.part(cat.name(), GL20.GL_TRIANGLES, attrs, materials.get(cat));
            for (BoxSpec b : ofCat) {
                addBox(part, b);
            }
        }
        sceneModel = mb.end();
        sceneInstance = new ModelInstance(sceneModel);
    }

    private void addBox(MeshPartBuilder part, BoxSpec b) {
        float hx = b.sizeX() / 2f, hy = b.sizeY() / 2f, hz = b.sizeZ() / 2f;
        float x0 = b.cx() - hx, x1 = b.cx() + hx;
        float y0 = b.cy() - hy, y1 = b.cy() + hy;
        float z0 = b.cz() - hz, z1 = b.cz() + hz;

        // +X and -X faces span (Z, Y).
        face(part, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, 1, 0, 0, b.sizeZ(), b.sizeY());
        face(part, x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1, -1, 0, 0, b.sizeZ(), b.sizeY());
        // +Y and -Y faces span (X, Z).
        face(part, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, 0, 1, 0, b.sizeX(), b.sizeZ());
        face(part, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, 0, -1, 0, b.sizeX(), b.sizeZ());
        // +Z and -Z faces span (X, Y).
        face(part, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, 0, 0, 1, b.sizeX(), b.sizeY());
        face(part, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, 0, 0, -1, b.sizeX(), b.sizeY());
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

    /** Sets (or clears with {@code null}) the translucent placement ghost and whether it's a valid spot. */
    public void setGhost(BoxSpec box, boolean valid) {
        this.ghostBox = box;
        this.ghostValid = valid;
    }

    /** Sets (or clears with {@code null}) the box currently highlighted by the crosshair. */
    public void setHighlight(BoxSpec box) {
        highlightActive = box != null;
        if (highlightActive) {
            highlightInstance.transform.setToTranslationAndScaling(
                    box.cx(), box.cy(), box.cz(),
                    box.sizeX() * 1.10f, box.sizeY() * 1.10f, box.sizeZ() * 1.10f);
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
        if (ghostBox != null) {
            ModelInstance ghost = ghostValid
                    ? ghostInstances.getOrDefault(ghostBox.category(), ghostInvalidInstance)
                    : ghostInvalidInstance;
            ghost.transform.setToTranslationAndScaling(
                    ghostBox.cx(), ghostBox.cy(), ghostBox.cz(),
                    ghostBox.sizeX(), ghostBox.sizeY(), ghostBox.sizeZ());
            modelBatch.render(ghost, environment);
        }
        modelBatch.end();
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
        for (Model m : ghostModels.values()) {
            m.dispose();
        }
        ghostInvalidBox.dispose();
        noiseTexture.dispose();
    }
}
