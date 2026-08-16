package com.minecart.display.render.snap;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.utils.Disposable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/**
 * Draws a snap board's {@link BoxSpec} scene in 3D. One flat-shaded unit cube {@link Model} is built per
 * {@link BoxSpec.Category} (colour), then reused as many {@link ModelInstance}s transformed to each box's
 * centre and size — so the whole board is a handful of models regardless of part count. The blocky,
 * flat-lit cubes give the intended pixelated/voxel look without any texture assets.
 *
 * <p>Instances are rebuilt only when {@link #setScene(List)} is called (i.e. when the board changes), not
 * per frame, so steady-state rendering allocates nothing.
 */
public final class SnapRenderer implements Disposable {

    private final ModelBatch modelBatch = new ModelBatch();
    private final Environment environment = new Environment();
    private final EnumMap<BoxSpec.Category, Model> unitBoxes = new EnumMap<>(BoxSpec.Category.class);
    private final List<ModelInstance> instances = new ArrayList<>();

    public SnapRenderer() {
        environment.set(new ColorAttribute(ColorAttribute.AmbientLight, 0.55f, 0.55f, 0.60f, 1f));
        DirectionalLight sun = new DirectionalLight();
        sun.set(0.9f, 0.9f, 0.85f, -0.4f, -1f, -0.6f);
        environment.add(sun);

        ModelBuilder mb = new ModelBuilder();
        long attrs = VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal;
        for (BoxSpec.Category cat : BoxSpec.Category.values()) {
            Model box = mb.createBox(1f, 1f, 1f,
                    new Material(ColorAttribute.createDiffuse(colorFor(cat))), attrs);
            unitBoxes.put(cat, box);
        }
    }

    /** Rebuilds the drawable instances from a fresh box list. Call when the board changes. */
    public void setScene(List<BoxSpec> boxes) {
        instances.clear();
        for (BoxSpec b : boxes) {
            Model model = unitBoxes.get(b.category());
            if (model == null) {
                model = unitBoxes.get(BoxSpec.Category.UNKNOWN);
            }
            ModelInstance instance = new ModelInstance(model);
            instance.transform.setToTranslationAndScaling(
                    b.cx(), b.cy(), b.cz(), b.sizeX(), b.sizeY(), b.sizeZ());
            instances.add(instance);
        }
    }

    /** Draws the current scene from {@code camera}'s viewpoint. */
    public void render(Camera camera) {
        modelBatch.begin(camera);
        for (ModelInstance instance : instances) {
            modelBatch.render(instance, environment);
        }
        modelBatch.end();
    }

    private static Color colorFor(BoxSpec.Category category) {
        return switch (category) {
            case BASE -> new Color(0.18f, 0.20f, 0.24f, 1f);
            case POST -> new Color(0.45f, 0.47f, 0.50f, 1f);
            case WIRE -> new Color(0.85f, 0.85f, 0.88f, 1f);
            case RESISTOR -> new Color(0.80f, 0.55f, 0.25f, 1f);
            case BATTERY -> new Color(0.30f, 0.70f, 0.35f, 1f);
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
    }
}
