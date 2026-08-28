package com.minecart.display.entity;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.bullet.dynamics.btRigidBody;

/**
 * A loose item that exists in the world with REAL physics — the physical form of e.g. a battery pulled out of
 * its box (while socketed it is instead data inside the box's component-entity, not an Entity). Its pose comes
 * from the Bullet {@link btRigidBody}; {@link #modelId} names the engine model to draw at that pose.
 */
public final class Entity {

    public final String modelId;
    private final btRigidBody body;
    private final Matrix4 pose = new Matrix4();

    Entity(String modelId, btRigidBody body) {
        this.modelId = modelId;
        this.body = body;
    }

    /** The current world transform, read from the physics body — feed this to the renderer each frame. */
    public Matrix4 pose() {
        body.getWorldTransform(pose);
        return pose;
    }

    /** The body's world position (centre). */
    public Vector3 position(Vector3 out) {
        return pose().getTranslation(out);
    }

    /** Gives the freshly-spawned entity a launch velocity (the "pop out of the socket" kick). */
    public void launch(Vector3 velocity) {
        body.activate(true);
        body.setLinearVelocity(velocity);
    }

    btRigidBody body() {
        return body;
    }
}
