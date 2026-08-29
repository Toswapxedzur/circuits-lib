package com.minecart.display.render.engine;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.bullet.Bullet;
import com.badlogic.gdx.physics.bullet.collision.btBoxShape;
import com.minecart.display.entity.Entity;
import com.minecart.display.entity.EntityWorld;

/**
 * The world-entity <b>lifecycle</b> demo (P2 + P3): a battery is <b>data inside a battery box</b> while socketed
 * (no physics), becomes a <b>physical {@link Entity}</b> when ejected (falls, tumbles, rests against the ramp at
 * an angle — real Bullet physics), and turns back into box data when re-inserted. Controls: <b>E</b> eject,
 * <b>Q</b> insert (only when the loose battery is near the box), <b>R</b> reset; <b>WASD</b>/Space/Shift + drag
 * to fly the camera.
 *
 * <p>Rendered <b>entirely through the instanced engine</b> (the g3d/SpriteBatch shaders don't compile in the
 * GL3.2 core context the engine needs, so everything is engine geometry): the loose battery is its real
 * {@code battery_cell} part model drawn at the Bullet pose via the {@link EngineRenderer.DynamicEntity
 * dynamic-entity} path (a full matrix — it tumbles and rests at an angle correctly); the ground, ramp, and
 * battery box are neutral {@code slab} models scaled/rotated into place. Run: {@code ./gradlew :display:entitydemo}.
 */
public final class EntityDemoApp extends ApplicationAdapter {

    private static final Vector3 SOCKET = new Vector3(0f, 8f, 0f);   // where an ejected battery spawns
    private static final float INSERT_REACH = 16f;                   // re-seat range around the box
    private static final Vector3 BATTERY_HALF = new Vector3(9f, 3f, 3f); // matches the battery_cell model (18×6×6)
    private static final Matrix4 SOCKETED_POSE = new Matrix4().setToTranslation(0f, 7f, 0f); // resting on the box

    private EntityWorld physics;
    private boolean socketed = true;
    private Entity battery;

    private PerspectiveCamera cam;
    private FlyController fly;
    private EngineRenderer engine;
    private EngineRenderer.DynamicEntity batteryEntity;
    private final Matrix4 batteryPose = new Matrix4();
    private final Matrix4 rampTx = new Matrix4();
    private final Vector3 tmp = new Vector3();

    @Override
    public void create() {
        Bullet.init();
        physics = new EntityWorld();
        physics.addStatic(new btBoxShape(new Vector3(40f, 1f, 40f)), new Matrix4().setToTranslation(0f, -1f, 0f)); // ground
        physics.addStatic(new btBoxShape(new Vector3(5f, 2f, 4f)), new Matrix4().setToTranslation(0f, 2f, 0f));    // box
        rampTx.setToTranslation(14f, 1.5f, 0f).rotate(Vector3.Z, 28f);
        physics.addStatic(new btBoxShape(new Vector3(9f, 1f, 9f)), rampTx);                                        // ramp (in the drop path)

        engine = new EngineRenderer();
        ModelLoader loader = new ModelLoader();
        // Scenery: neutral grey slabs (unit cube model, scaled/rotated into place via the full-matrix entity path).
        addSlab(loader, new Matrix4().setToTranslation(0f, -1f, 0f).scale(80f, 2f, 80f));  // ground
        addSlab(loader, new Matrix4().set(rampTx).scale(18f, 2f, 18f));                     // ramp
        addSlab(loader, new Matrix4().setToTranslation(0f, 2f, 0f).scale(10f, 4f, 8f));     // battery box
        batteryEntity = new EngineRenderer.DynamicEntity(loader.model("battery_cell"));
        batteryEntity.pose(SOCKETED_POSE);
        engine.addEntity(batteryEntity);
        engine.build();

        cam = new PerspectiveCamera(60f, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        cam.position.set(40f, 30f, 50f);
        cam.near = 0.5f;
        cam.far = 800f;
        cam.lookAt(8f, 3f, 0f);
        cam.up.set(0f, 1f, 0f);
        cam.update();
        fly = new FlyController(cam, 60f);
        Gdx.input.setInputProcessor(fly);
    }

    private void addSlab(ModelLoader loader, Matrix4 pose) {
        EngineRenderer.DynamicEntity e = new EngineRenderer.DynamicEntity(loader.model("slab"));
        e.pose(pose);
        engine.addEntity(e);
    }

    private void eject() {
        if (!socketed || battery != null) return;
        battery = physics.spawn("battery_cell", new btBoxShape(BATTERY_HALF), 1.2f,
                new Matrix4().setToTranslation(SOCKET.x, SOCKET.y, SOCKET.z));
        battery.launch(new Vector3(13f, 9f, 0f)); // pop up and out onto the ramp
        socketed = false;
    }

    private void insert() {
        if (socketed || battery == null) return;
        if (battery.position(tmp).dst(SOCKET) > INSERT_REACH) return;
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
        float dt = Math.min(Gdx.graphics.getDeltaTime(), 1f / 30f);
        fly.update(dt);
        physics.step(dt);

        // The battery: its real part model at the socket (data-in-box) or at the live Bullet pose (ejected entity).
        batteryEntity.pose(socketed || battery == null ? SOCKETED_POSE : batteryPose.set(battery.pose()));

        Gdx.gl.glClearColor(0.11f, 0.12f, 0.15f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        engine.render(cam);
    }

    @Override
    public void resize(int width, int height) {
        cam.viewportWidth = width;
        cam.viewportHeight = height;
        cam.update();
    }

    @Override
    public void dispose() {
        engine.dispose();
        physics.dispose();
    }

    public static void main(String[] args) {
        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("Entity Demo — battery box <-> physical battery (E eject / Q insert / R reset)");
        config.setWindowedMode(1100, 720);
        config.setBackBufferConfig(8, 8, 8, 8, 24, 0, 4);
        config.setOpenGLEmulation(Lwjgl3ApplicationConfiguration.GLEmulation.GL30, 3, 2); // the engine instances need GL3+
        config.setForegroundFPS(60);
        new Lwjgl3Application(new EntityDemoApp(), config);
    }
}
