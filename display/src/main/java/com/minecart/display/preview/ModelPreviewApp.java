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
 * Standalone libGDX model viewer for iterating on part 3D models. Shows several shading variants side by side
 * (each labelled with its parameters) so a good look can be picked at a glance, under bright, even "studio"
 * lighting — this is a viewer, not the game scene, so the game's no-sun / component-light-only rule does not
 * apply. Drag to orbit, scroll to zoom. Run with {@code ./gradlew :display:preview}.
 */
public final class ModelPreviewApp extends ApplicationAdapter {

    private static final Color BG = new Color(0.11f, 0.12f, 0.15f, 1f);
    private static final float SPACING = 56f;

    // Chosen look: top-lit, shift 3.5 (light from above-front, body a little darker at the bottom).
    private static final PreviewPart.Shading[] VARIANTS = {
            new PreviewPart.Shading("top-lit x3.5", new Vector3(0.5f, 0.7f, 0.4f), 3.5f),
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

        parts = new PreviewPart[VARIANTS.length];
        offsets = new float[VARIANTS.length];
        float mid = (VARIANTS.length - 1) / 2f;
        for (int i = 0; i < VARIANTS.length; i++) {
            parts[i] = new PreviewPart(VARIANTS[i]);
            offsets[i] = (i - mid) * SPACING;
            parts[i].instance().transform.setToTranslation(offsets[i], 0f, 0f);
        }

        // Frame the whole row: back the camera off proportionally to how wide the row is.
        float rowHalfWidth = (VARIANTS.length - 1) * SPACING / 2f + 20f;
        Vector3 target = PreviewPart.center();
        cam = new PerspectiveCamera(55f, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        cam.position.set(target).add(0f, rowHalfWidth * 0.35f, rowHalfWidth * 1.7f);
        cam.near = 0.5f;
        cam.far = 4000f;
        cam.lookAt(target);
        cam.update();

        camController = new CameraInputController(cam);
        camController.target.set(target);
        camController.translateUnits = rowHalfWidth * 2f;
        Gdx.input.setInputProcessor(camController);

        // Bright, even studio lighting; the model's own baked gradient supplies the "shading" we're judging.
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
        font.draw(ui, "Capacitor — shading variants   (drag: orbit   scroll: zoom)", 16f, Gdx.graphics.getHeight() - 14f);
        for (int i = 0; i < parts.length; i++) {
            tmp.set(offsets[i], -4f, 0f);
            cam.project(tmp);
            if (tmp.z > 1f) {
                continue; // behind the camera
            }
            layout.setText(font, VARIANTS[i].label());
            font.setColor(0.72f, 0.76f, 0.8f, 1f);
            font.draw(ui, VARIANTS[i].label(), tmp.x - layout.width / 2f, tmp.y);
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
        config.setTitle("Model Preview — Capacitor");
        config.setWindowedMode(1280, 760);
        config.setBackBufferConfig(8, 8, 8, 8, 16, 0, 4); // 4x MSAA for clean low-poly edges
        config.setForegroundFPS(60);
        new Lwjgl3Application(new ModelPreviewApp(), config);
    }
}
