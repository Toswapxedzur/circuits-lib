package com.minecart.display.preview;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.utils.CameraInputController;
import com.badlogic.gdx.math.Vector3;

/**
 * Model viewer for the plastic part series — renders the finished capacitor in each chosen body colour
 * ({@link PlasticColors}) in a row, under bright even "studio" lighting. Drag to orbit, scroll to zoom.
 * Run with {@code ./gradlew :display:preview}.
 */
public final class ModelPreviewApp extends ApplicationAdapter {

    private static final Color BG = new Color(0.11f, 0.12f, 0.15f, 1f);

    /** Fixed series lighting (top-lit, subtle), same for every part. */
    private static final PreviewPart.Shading SHADING =
            new PreviewPart.Shading("top-lit", new Vector3(0.5f, 0.7f, 0.4f), 3.5f);

    private static final float SPACING = 46f;

    /** What to show: the parts of the series, rendered in lime for now. */
    private record Spec(String name, PreviewPart.PartType type) {}

    private static final Spec[] SPECS = {
            new Spec("capacitor", PreviewPart.PartType.CAPACITOR),
            new Spec("switch", PreviewPart.PartType.SWITCH),
    };

    private PerspectiveCamera cam;
    private CameraInputController camController;
    private ModelBatch modelBatch;
    private Environment environment;
    private PreviewPart[] parts;
    private float[] offsets;

    private SpriteBatch ui;
    private BitmapFont font;
    private GlyphLayout layout;
    private final Vector3 tmp = new Vector3();

    @Override
    public void create() {
        modelBatch = new ModelBatch();

        // Lime is the base colour for reviewing new part designs.
        Color[] lime = PlasticColors.palette(PlasticColors.SET[3]);
        int n = SPECS.length;
        parts = new PreviewPart[n];
        offsets = new float[n];
        float mid = (n - 1) / 2f;
        for (int i = 0; i < n; i++) {
            parts[i] = new PreviewPart(SHADING, lime, SPECS[i].type());
            offsets[i] = (i - mid) * SPACING;
            parts[i].instance().transform.setToTranslation(offsets[i], 0f, 0f);
        }

        float reach = mid * SPACING + 30f;
        Vector3 target = PreviewPart.center();
        cam = new PerspectiveCamera(55f, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        cam.position.set(target).add(0f, reach * 1.05f, reach * 1.15f);
        cam.near = 0.5f;
        cam.far = 6000f;
        cam.lookAt(target);
        cam.update();

        camController = new CameraInputController(cam);
        camController.target.set(target);
        camController.translateUnits = reach * 3f;
        Gdx.input.setInputProcessor(camController);

        environment = new Environment();
        environment.set(ColorAttribute.createAmbientLight(0.93f, 0.93f, 0.96f, 1f));
        environment.add(new DirectionalLight().set(0.14f, 0.14f, 0.15f, -0.45f, -0.5f, -0.55f));

        ui = new SpriteBatch();
        font = new BitmapFont();
        layout = new GlyphLayout();
    }

    @Override
    public void render() {
        camController.update();

        Gdx.gl.glClearColor(BG.r, BG.g, BG.b, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);

        modelBatch.begin(cam);
        for (PreviewPart p : parts) {
            modelBatch.render(p.instance(), environment);
        }
        modelBatch.end();

        ui.begin();
        font.setColor(0.88f, 0.9f, 0.93f, 1f);
        font.draw(ui, "Plastic series (lime)    drag: orbit   scroll: zoom", 16f, Gdx.graphics.getHeight() - 12f);
        for (int i = 0; i < parts.length; i++) {
            tmp.set(offsets[i], -4f, 0f);
            cam.project(tmp);
            if (tmp.z > 1f) {
                continue;
            }
            String name = SPECS[i].name();
            layout.setText(font, name);
            font.setColor(0.74f, 0.78f, 0.82f, 1f);
            font.draw(ui, name, tmp.x - layout.width / 2f, tmp.y);
        }
        ui.end();
    }

    @Override
    public void resize(int width, int height) {
        cam.viewportWidth = width;
        cam.viewportHeight = height;
        cam.update();
    }

    @Override
    public void dispose() {
        modelBatch.dispose();
        for (PreviewPart p : parts) {
            p.dispose();
        }
        ui.dispose();
        font.dispose();
    }

    public static void main(String[] args) {
        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("Model Preview — Plastic Series");
        config.setWindowedMode(1500, 820);
        config.setBackBufferConfig(8, 8, 8, 8, 16, 0, 4); // 4x MSAA
        config.setForegroundFPS(60);
        new Lwjgl3Application(new ModelPreviewApp(), config);
    }
}
