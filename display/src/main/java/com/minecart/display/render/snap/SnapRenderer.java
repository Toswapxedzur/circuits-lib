package com.minecart.display.render.snap;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.utils.Disposable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Random;

/**
 * Draws a snap board's {@link BoxSpec} scene in 3D. Each {@link BoxSpec.Category} gets one flat-shaded
 * unit-cube {@link Model} whose material is a chosen diffuse colour multiplied by a shared procedural
 * <em>noise</em> texture (nearest-filtered, so it reads as chunky pixels) and lit by a directional light —
 * giving the pixelated, lightly-varied surface the design calls for without any texture assets. Those
 * models are reused as transformed {@link ModelInstance}s per box, so a board is a handful of models
 * regardless of part count.
 *
 * <p>A separate translucent emissive cube is drawn around the ray-picked part as a hover highlight.
 */
public final class SnapRenderer implements Disposable {

    private static final int NOISE_SIZE = 32;
    private static final long NOISE_SEED = 0x5AFE_C0DEL;

    private final ModelBatch modelBatch = new ModelBatch();
    private final Environment environment = new Environment();
    private final EnumMap<BoxSpec.Category, Model> unitBoxes = new EnumMap<>(BoxSpec.Category.class);
    private final List<ModelInstance> instances = new ArrayList<>();
    private final Texture noiseTexture;

    private final Model highlightBox;
    private final ModelInstance highlightInstance;
    private boolean highlightActive;

    public SnapRenderer() {
        environment.set(new ColorAttribute(ColorAttribute.AmbientLight, 0.45f, 0.45f, 0.50f, 1f));
        DirectionalLight sun = new DirectionalLight();
        sun.set(0.95f, 0.95f, 0.90f, -0.45f, -1f, -0.65f);
        environment.add(sun);

        this.noiseTexture = buildNoiseTexture();

        ModelBuilder mb = new ModelBuilder();
        long attrs = VertexAttributes.Usage.Position
                | VertexAttributes.Usage.Normal
                | VertexAttributes.Usage.TextureCoordinates;
        for (BoxSpec.Category cat : BoxSpec.Category.values()) {
            Material material = new Material(
                    ColorAttribute.createDiffuse(colorFor(cat)),
                    TextureAttribute.createDiffuse(noiseTexture));
            unitBoxes.put(cat, mb.createBox(1f, 1f, 1f, material, attrs));
        }

        // Translucent emissive cube used as the hover highlight (drawn slightly larger around a part).
        Material hl = new Material(
                ColorAttribute.createDiffuse(1f, 0.9f, 0.3f, 1f),
                ColorAttribute.createEmissive(0.8f, 0.7f, 0.2f, 1f),
                new BlendingAttribute(0.35f));
        this.highlightBox = mb.createBox(1f, 1f, 1f, hl,
                VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal);
        this.highlightInstance = new ModelInstance(highlightBox);
    }

    /** Rebuilds the drawable instances from a scene. Call when the board changes. */
    public void setScene(SnapScene scene) {
        instances.clear();
        for (BoxSpec b : scene.boxes()) {
            Model model = unitBoxes.getOrDefault(b.category(), unitBoxes.get(BoxSpec.Category.UNKNOWN));
            ModelInstance instance = new ModelInstance(model);
            instance.transform.setToTranslationAndScaling(
                    b.cx(), b.cy(), b.cz(), b.sizeX(), b.sizeY(), b.sizeZ());
            instances.add(instance);
        }
    }

    /** Sets (or clears with {@code null}) the box currently highlighted by the cursor. */
    public void setHighlight(BoxSpec box) {
        highlightActive = box != null;
        if (highlightActive) {
            highlightInstance.transform.setToTranslationAndScaling(
                    box.cx(), box.cy(), box.cz(),
                    box.sizeX() * 1.10f, box.sizeY() * 1.10f, box.sizeZ() * 1.10f);
        }
    }

    /** Draws the current scene (and any active highlight) from {@code camera}'s viewpoint. */
    public void render(Camera camera) {
        modelBatch.begin(camera);
        for (ModelInstance instance : instances) {
            modelBatch.render(instance, environment);
        }
        if (highlightActive) {
            modelBatch.render(highlightInstance, environment);
        }
        modelBatch.end();
    }

    private static Texture buildNoiseTexture() {
        Pixmap pixmap = new Pixmap(NOISE_SIZE, NOISE_SIZE, Pixmap.Format.RGBA8888);
        Random random = new Random(NOISE_SEED);
        for (int y = 0; y < NOISE_SIZE; y++) {
            for (int x = 0; x < NOISE_SIZE; x++) {
                // Bright-ish grey noise so it modulates the diffuse colour without darkening it much.
                float v = 0.62f + random.nextFloat() * 0.38f;
                pixmap.setColor(v, v, v, 1f);
                pixmap.drawPixel(x, y);
            }
        }
        Texture texture = new Texture(pixmap);
        texture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        pixmap.dispose();
        return texture;
    }

    private static Color colorFor(BoxSpec.Category category) {
        return switch (category) {
            case BASE -> new Color(0.20f, 0.22f, 0.27f, 1f);
            case POST -> new Color(0.52f, 0.54f, 0.58f, 1f);
            case WIRE -> new Color(0.82f, 0.84f, 0.88f, 1f);
            case RESISTOR -> new Color(0.86f, 0.52f, 0.20f, 1f);
            case BATTERY -> new Color(0.28f, 0.72f, 0.38f, 1f);
            case UNKNOWN -> Color.MAGENTA;
        };
    }

    @Override
    public void dispose() {
        modelBatch.dispose();
        for (Model model : unitBoxes.values()) {
            model.dispose();
        }
        unitBoxes.clear();
        instances.clear();
        highlightBox.dispose();
        noiseTexture.dispose();
    }
}
