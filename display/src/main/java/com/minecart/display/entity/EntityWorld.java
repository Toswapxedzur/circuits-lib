package com.minecart.display.entity;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.bullet.collision.btBroadphaseInterface;
import com.badlogic.gdx.physics.bullet.collision.btCollisionConfiguration;
import com.badlogic.gdx.physics.bullet.collision.btCollisionDispatcher;
import com.badlogic.gdx.physics.bullet.collision.btCollisionShape;
import com.badlogic.gdx.physics.bullet.collision.btDbvtBroadphase;
import com.badlogic.gdx.physics.bullet.collision.btDefaultCollisionConfiguration;
import com.badlogic.gdx.physics.bullet.dynamics.btConstraintSolver;
import com.badlogic.gdx.physics.bullet.dynamics.btDiscreteDynamicsWorld;
import com.badlogic.gdx.physics.bullet.dynamics.btRigidBody;
import com.badlogic.gdx.physics.bullet.dynamics.btSequentialImpulseConstraintSolver;
import com.badlogic.gdx.physics.bullet.linearmath.btDefaultMotionState;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;

import java.util.ArrayList;
import java.util.List;

/**
 * The world's rigid-body physics — a thin wrapper over Bullet ({@code gdx-bullet}). Holds a discrete dynamics
 * world with gravity; STATIC colliders (the ground, a placed part) have mass 0, dynamic {@link Entity}s (a
 * pulled-out battery) have real mass and fall/collide/rest at arbitrary angles. Call {@link com.badlogic.gdx.physics.bullet.Bullet#init()}
 * once before constructing one. {@link #step} advances the simulation; read each entity's {@link Entity#pose()}
 * for rendering. Bullet objects are JNI-backed, so this keeps hard references to every native object it creates
 * (Java GC must not collect them out from under Bullet) and disposes them in {@link #dispose()}.
 */
public final class EntityWorld implements Disposable {

    private final btCollisionConfiguration collisionConfig;
    private final btCollisionDispatcher dispatcher;
    private final btBroadphaseInterface broadphase;
    private final btConstraintSolver solver;
    private final btDiscreteDynamicsWorld world;

    private final List<Entity> entities = new ArrayList<>();
    private final Array<btRigidBody> bodies = new Array<>();
    private final Array<btDefaultMotionState> motions = new Array<>();
    private final Array<btCollisionShape> shapes = new Array<>();

    public EntityWorld() {
        collisionConfig = new btDefaultCollisionConfiguration();
        dispatcher = new btCollisionDispatcher(collisionConfig);
        broadphase = new btDbvtBroadphase();
        solver = new btSequentialImpulseConstraintSolver();
        world = new btDiscreteDynamicsWorld(dispatcher, broadphase, solver, collisionConfig);
        world.setGravity(new Vector3(0f, -30f, 0f));
    }

    /** Adds an immovable collider (ground, ramp, a placed snap part) — mass 0. */
    public void addStatic(btCollisionShape shape, Matrix4 transform) {
        addBody(shape, 0f, transform);
    }

    /** Spawns a dynamic ENTITY (a loose item with real physics) and returns it for rendering. */
    public Entity spawn(String modelId, btCollisionShape shape, float mass, Matrix4 transform) {
        Entity e = new Entity(modelId, addBody(shape, mass, transform));
        entities.add(e);
        return e;
    }

    private btRigidBody addBody(btCollisionShape shape, float mass, Matrix4 transform) {
        Vector3 inertia = new Vector3();
        if (mass > 0f) shape.calculateLocalInertia(mass, inertia);
        btDefaultMotionState motion = new btDefaultMotionState(transform);
        btRigidBody.btRigidBodyConstructionInfo info =
                new btRigidBody.btRigidBodyConstructionInfo(mass, motion, shape, inertia);
        btRigidBody body = new btRigidBody(info);
        body.setFriction(0.9f);
        world.addRigidBody(body);
        info.dispose();
        bodies.add(body);
        motions.add(motion);
        shapes.add(shape);
        return body;
    }

    public List<Entity> entities() {
        return entities;
    }

    /** Removes a dynamic entity from the simulation (e.g. the battery being re-socketed → back to box data). */
    public void despawn(Entity e) {
        world.removeRigidBody(e.body());
        entities.remove(e);
        // JNI body/motion/shape stay in the keep-arrays and are freed in dispose() (fine for short-lived demos).
    }

    /** Advances the simulation, sub-stepping at a fixed 1/120 s for stability. */
    public void step(float dt) {
        world.stepSimulation(dt, 5, 1f / 120f);
    }

    @Override
    public void dispose() {
        for (btRigidBody b : bodies) {
            world.removeRigidBody(b);
            b.dispose();
        }
        for (btDefaultMotionState m : motions) m.dispose();
        for (btCollisionShape s : shapes) s.dispose();
        world.dispose();
        solver.dispose();
        broadphase.dispose();
        dispatcher.dispose();
        collisionConfig.dispose();
    }
}
