package com.minecart.display.render.engine;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;

import java.util.ArrayList;
import java.util.List;

/**
 * One placed component: a {@link ComponentModel} at a world transform, with its own {@link AnimationState}.
 * Its <b>static</b> boxes are contributed (world-placed) to the scene mesh at build time via
 * {@link #collectStatic}; its <b>movable</b> parts live as {@link PartInstance}s whose world matrices
 * {@link #update} recomputes each frame from the eased animation. (Placement is translation for now; a rotated
 * placement would need axis-aligned dim-swaps for the static boxes — added when a rotated part exists.)
 */
final class ComponentInstance {

    /** A live instance of a movable part-type, with its recomputed world transform. */
    static final class PartInstance {
        final PartType type;
        final Matrix4 local;
        final MovableBinding binding;
        final Matrix4 world = new Matrix4();

        PartInstance(PartType type, Matrix4 local, MovableBinding binding) {
            this.type = type;
            this.local = local;
            this.binding = binding;
        }
    }

    final AnimationState anim = new AnimationState();
    final List<PartInstance> movables = new ArrayList<>();
    private final ComponentModel model;
    private final Matrix4 world = new Matrix4();
    private final Matrix4 motion = new Matrix4();

    ComponentInstance(ComponentModel model, Matrix4 world) {
        this.model = model;
        this.world.set(world);
        for (ComponentModel.MovablePart m : model.movableParts) {
            movables.add(new PartInstance(m.type(), m.local(), m.binding().toBinding()));
        }
        update(0f);
    }

    /** Eases the animation and recomputes each movable part's world matrix. */
    void update(float dt) {
        anim.update(dt);
        for (PartInstance p : movables) {
            p.world.set(world).mul(p.local);
            p.world.mul(p.binding.motion(anim, motion));
        }
    }

    /** Adds this component's static boxes, translated to world space, into {@code out} (for the scene mesh). */
    void collectStatic(List<PartMesh.Box> out) {
        float tx = world.val[Matrix4.M03], ty = world.val[Matrix4.M13], tz = world.val[Matrix4.M23];
        for (PartMesh.Box b : model.staticBoxes) {
            // Translate geometry to world, but KEEP the object-space centre so the baked shading gradient is
            // identical for every instance (and instances share one sprite).
            out.add(new PartMesh.Box(b.cx() + tx, b.cy() + ty, b.cz() + tz, b.sx(), b.sy(), b.sz(), b.paint(),
                    b.ocx(), b.ocy(), b.ocz(), b.faceSprites()));
        }
    }

    /** Adds this component's oriented quads, translated to world space (object corners kept for shading). */
    void collectQuads(List<PartMesh.Quad> out) {
        float tx = world.val[Matrix4.M03], ty = world.val[Matrix4.M13], tz = world.val[Matrix4.M23];
        for (PartMesh.Quad q : model.staticQuads) {
            out.add(new PartMesh.Quad(
                    new Vector3(q.p00()).add(tx, ty, tz), new Vector3(q.p10()).add(tx, ty, tz),
                    new Vector3(q.p11()).add(tx, ty, tz), new Vector3(q.p01()).add(tx, ty, tz),
                    q.o00(), q.o10(), q.o11(), q.o01(),
                    q.paint(), q.pw(), q.ph(), q.bakedSprite()));
        }
    }
}
