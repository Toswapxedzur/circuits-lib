package com.minecart.display.entity;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.bullet.Bullet;
import com.badlogic.gdx.physics.bullet.collision.btBoxShape;

/**
 * Bullet physics PROOF for the world-entity system: a dynamic entity box is dropped above a tilted static ramp;
 * it falls, hits the ramp, and comes to rest at an angle — the exact effect the owner described ("latch off
 * another plastic component with an angle/inclination"). Rendered with plain libGDX g3d boxes (NOT the instanced
 * engine — that dynamic-entity render path is a later, coordinated step). Run: {@code ./gradlew :display:entityproof}.
 */
public final class EntityPhysicsProof extends ApplicationAdapter {

    private EntityWorld physics;
    private Entity dropped;

    private ModelBatch batch;
    private Environment env;
    private PerspectiveCamera cam;
    private Model box;
    private ModelInstance groundInst, rampInst, entityInst;
    private final Matrix4 rampTransform = new Matrix4();

    @Override
    public void create() {
        Bullet.init();
        physics = new EntityWorld();

        // Static ground (half-extents 40×1×40) at y=-1, and a ramp (half-extents 12×1×8) tilted 30° about Z.
        physics.addStatic(new btBoxShape(new Vector3(40f, 1f, 40f)), new Matrix4().setToTranslation(0f, -1f, 0f));
        rampTransform.setToTranslation(0f, 4f, 0f).rotate(Vector3.Z, 30f);
        physics.addStatic(new btBoxShape(new Vector3(12f, 1f, 8f)), rampTransform);

        // The dynamic entity: a 4×4×4 box (half-extents 2) dropped above the high side of the ramp.
        dropped = physics.spawn("battery", new btBoxShape(new Vector3(2f, 2f, 2f)), 1f,
                new Matrix4().setToTranslation(7f, 22f, 0f));

        ModelBuilder mb = new ModelBuilder();
        long attrs = VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal;
        box = mb.createBox(1f, 1f, 1f, new Material(ColorAttribute.createDiffuse(Color.WHITE)), attrs);
        groundInst = tinted(new Color(0.35f, 0.37f, 0.42f, 1f));
        groundInst.transform.set(new Matrix4().setToTranslation(0f, -1f, 0f)).scale(80f, 2f, 80f);
        rampInst = tinted(new Color(0.2f, 0.7f, 0.7f, 1f));
        rampInst.transform.set(rampTransform).scale(24f, 2f, 16f);
        entityInst = tinted(new Color(0.9f, 0.2f, 0.2f, 1f));

        env = new Environment();
        env.set(new ColorAttribute(ColorAttribute.AmbientLight, 0.5f, 0.5f, 0.55f, 1f));
        env.add(new DirectionalLight().set(0.9f, 0.9f, 0.85f, -0.6f, -0.9f, -0.4f));
        batch = new ModelBatch();

        cam = new PerspectiveCamera(60f, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        cam.position.set(34f, 26f, 46f);
        cam.lookAt(0f, 4f, 0f);
        cam.near = 0.5f;
        cam.far = 500f;
        cam.update();
    }

    private ModelInstance tinted(Color c) {
        ModelInstance mi = new ModelInstance(box);
        mi.materials.get(0).set(ColorAttribute.createDiffuse(c));
        return mi;
    }

    @Override
    public void render() {
        physics.step(Math.min(Gdx.graphics.getDeltaTime(), 1f / 30f));
        entityInst.transform.set(dropped.pose()).scale(4f, 4f, 4f); // pose is the box centre; scale to its size

        Gdx.gl.glClearColor(0.1f, 0.11f, 0.14f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        batch.begin(cam);
        batch.render(groundInst, env);
        batch.render(rampInst, env);
        batch.render(entityInst, env);
        batch.end();
    }

    @Override
    public void resize(int width, int height) {
        cam.viewportWidth = width;
        cam.viewportHeight = height;
        cam.update();
    }

    @Override
    public void dispose() {
        batch.dispose();
        box.dispose();
        physics.dispose();
    }

    public static void main(String[] args) {
        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("Entity Physics Proof — Bullet drop onto a ramp");
        config.setWindowedMode(1100, 720);
        config.setBackBufferConfig(8, 8, 8, 8, 24, 0, 4);
        new Lwjgl3Application(new EntityPhysicsProof(), config);
    }
}
