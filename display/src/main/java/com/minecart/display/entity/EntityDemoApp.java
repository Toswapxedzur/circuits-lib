package com.minecart.display.entity;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
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
 * The world-entity <b>lifecycle</b> demo (P2 + P3): a battery is <b>data inside a battery box</b> while
 * socketed (no physics), becomes a <b>physical {@link Entity}</b> when ejected (falls, tumbles, rests against
 * the ramp at an angle — real Bullet physics), and turns back into box data when re-inserted. Controls:
 * <b>E</b> eject, <b>Q</b> insert (only if the loose battery is near the box), <b>R</b> reset.
 *
 * <p>Rendered with plain libGDX g3d boxes on purpose — the "draw the entity as its real instanced-engine part
 * model at the physics pose" swap is the one step that touches {@code render/engine}, so it's deferred to a
 * coordinated pass. Run: {@code ./gradlew :display:entitydemo}.
 */
public final class EntityDemoApp extends ApplicationAdapter {

    // The battery box's socket (top-centre) and the reach within which a loose battery can be re-inserted.
    private static final Vector3 SOCKET = new Vector3(0f, 6f, 0f);
    private static final float INSERT_REACH = 9f;

    private EntityWorld physics;
    private boolean socketed = true;   // the box's component-entity data: is the battery inside?
    private Entity battery;            // the loose physical battery when ejected (else null)

    private ModelBatch batch;
    private Environment env;
    private PerspectiveCamera cam;
    private Model unit;                // a 1×1×1 box, scaled per instance
    private ModelInstance ground, ramp, boxInst, socketNub, batteryInst;
    private final Matrix4 rampTx = new Matrix4();
    private SpriteBatch ui;
    private BitmapFont font;
    private final Vector3 tmp = new Vector3();

    @Override
    public void create() {
        Bullet.init();
        physics = new EntityWorld();
        physics.addStatic(new btBoxShape(new Vector3(40f, 1f, 40f)), new Matrix4().setToTranslation(0f, -1f, 0f));
        physics.addStatic(new btBoxShape(new Vector3(4f, 2f, 5f)), new Matrix4().setToTranslation(0f, 2f, 0f)); // the box
        rampTx.setToTranslation(15f, 2f, 0f).rotate(Vector3.Z, 22f);
        physics.addStatic(new btBoxShape(new Vector3(8f, 1f, 6f)), rampTx);                                     // the ramp

        ModelBuilder mb = new ModelBuilder();
        long a = VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal;
        unit = mb.createBox(1f, 1f, 1f, new Material(ColorAttribute.createDiffuse(Color.WHITE)), a);
        ground = inst(new Color(0.33f, 0.35f, 0.40f, 1f), new Matrix4().setToTranslation(0f, -1f, 0f).scale(80f, 2f, 80f));
        ramp = inst(new Color(0.22f, 0.62f, 0.66f, 1f), new Matrix4().set(rampTx).scale(16f, 2f, 12f));
        boxInst = inst(new Color(0.20f, 0.21f, 0.24f, 1f), new Matrix4().setToTranslation(0f, 2f, 0f).scale(8f, 4f, 10f));
        socketNub = inst(new Color(0.30f, 0.85f, 0.35f, 1f), new Matrix4()); // the green battery shown inside the box
        batteryInst = inst(new Color(0.30f, 0.85f, 0.35f, 1f), new Matrix4());

        env = new Environment();
        env.set(new ColorAttribute(ColorAttribute.AmbientLight, 0.5f, 0.5f, 0.55f, 1f));
        env.add(new DirectionalLight().set(0.95f, 0.95f, 0.9f, -0.5f, -0.9f, -0.35f));
        batch = new ModelBatch();
        ui = new SpriteBatch();
        font = new BitmapFont();

        cam = new PerspectiveCamera(60f, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        cam.position.set(30f, 22f, 40f);
        cam.lookAt(4f, 3f, 0f);
        cam.near = 0.5f;
        cam.far = 500f;
        cam.update();
    }

    private ModelInstance inst(Color c, Matrix4 transform) {
        ModelInstance mi = new ModelInstance(unit);
        mi.materials.get(0).set(ColorAttribute.createDiffuse(c));
        mi.transform.set(transform);
        return mi;
    }

    private void eject() {
        if (!socketed || battery != null) return;
        Matrix4 at = new Matrix4().setToTranslation(SOCKET.x, SOCKET.y, SOCKET.z);
        battery = physics.spawn("battery", new btBoxShape(new Vector3(1.5f, 3f, 1.5f)), 1.2f, at);
        battery.launch(new Vector3(13f, 9f, 0f)); // pop up and out toward the ramp
        socketed = false;
    }

    private void insert() {
        if (socketed || battery == null) return;
        if (battery.position(tmp).dst(SOCKET) > INSERT_REACH) return; // too far away to re-seat
        physics.despawn(battery);
        battery = null;
        socketed = true;
    }

    private void reset() {
        if (battery != null) {
            physics.despawn(battery);
            battery = null;
        }
        socketed = true;
    }

    @Override
    public void render() {
        if (Gdx.input.isKeyJustPressed(Keys.E)) eject();
        if (Gdx.input.isKeyJustPressed(Keys.Q)) insert();
        if (Gdx.input.isKeyJustPressed(Keys.R)) reset();
        physics.step(Math.min(Gdx.graphics.getDeltaTime(), 1f / 30f));

        Gdx.gl.glClearColor(0.1f, 0.11f, 0.14f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        batch.begin(cam);
        batch.render(ground, env);
        batch.render(ramp, env);
        batch.render(boxInst, env);
        if (socketed) {
            socketNub.transform.setToTranslation(SOCKET.x, SOCKET.y - 1f, SOCKET.z).scale(3f, 6f, 3f);
            batch.render(socketNub, env);
        } else if (battery != null) {
            batteryInst.transform.set(battery.pose()).scale(3f, 6f, 3f);
            batch.render(batteryInst, env);
        }
        batch.end();

        ui.begin();
        boolean near = battery != null && battery.position(tmp).dst(SOCKET) <= INSERT_REACH;
        font.draw(ui, "Battery entity demo   —   [E] eject   [Q] insert" + (near || socketed ? "" : " (move nearer)")
                + "   [R] reset", 16f, Gdx.graphics.getHeight() - 16f);
        font.draw(ui, "battery: " + (socketed ? "SOCKETED (data in the box)" : "EJECTED (physical entity)"),
                16f, Gdx.graphics.getHeight() - 40f);
        ui.end();
    }

    @Override
    public void resize(int width, int height) {
        cam.viewportWidth = width;
        cam.viewportHeight = height;
        cam.update();
        ui.getProjectionMatrix().setToOrtho2D(0f, 0f, width, height);
    }

    @Override
    public void dispose() {
        batch.dispose();
        unit.dispose();
        ui.dispose();
        font.dispose();
        physics.dispose();
    }

    public static void main(String[] args) {
        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("Entity Demo — battery box <-> physical battery");
        config.setWindowedMode(1100, 720);
        config.setBackBufferConfig(8, 8, 8, 8, 24, 0, 4);
        new Lwjgl3Application(new EntityDemoApp(), config);
    }
}
