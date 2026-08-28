package com.minecart.display.render.engine;

import com.badlogic.gdx.math.Matrix4;

import java.util.ArrayList;
import java.util.List;

/**
 * A component's visual template, split the Create way:
 * <ul>
 *   <li><b>static boxes</b> (local space) — merged into the one neighbour-culled <i>scene</i> mesh, so faces
 *       hidden by the board or an adjacent component are dropped; not instanced;</li>
 *   <li><b>movable parts</b> — each a {@link PartType} + local placement + {@link MovableBinding}, kept as a
 *       GPU-instanced part that moves from the component's {@link AnimationState}.</li>
 * </ul>
 */
final class ComponentModel {

    /** A movable sub-part: its part-type, placement within the component, and how it moves (as serialisable data). */
    record MovablePart(PartType type, Matrix4 local, BindingSpec binding) {}

    final String id;
    final List<PartMesh.Box> staticBoxes;
    final List<MovablePart> movableParts;

    private ComponentModel(String id, List<PartMesh.Box> staticBoxes, List<MovablePart> movableParts) {
        this.id = id;
        this.staticBoxes = staticBoxes;
        this.movableParts = movableParts;
    }

    static Builder of(String id) {
        return new Builder(id);
    }

    static final class Builder {
        private final String id;
        private final List<PartMesh.Box> statics = new ArrayList<>();
        private final List<MovablePart> movables = new ArrayList<>();

        private Builder(String id) {
            this.id = id;
        }

        Builder box(float cx, float cy, float cz, float sx, float sy, float sz, PaletteDither.Paint paint) {
            statics.add(PartMesh.Box.local(cx, cy, cz, sx, sy, sz, paint));
            return this;
        }

        /** A box whose object-space shading centre differs from where it sits (e.g. a movable part's rest pose). */
        Builder boxAt(float cx, float cy, float cz, float sx, float sy, float sz, PaletteDither.Paint paint,
                      float ocx, float ocy, float ocz) {
            statics.add(new PartMesh.Box(cx, cy, cz, sx, sy, sz, paint, ocx, ocy, ocz, null));
            return this;
        }

        Builder movable(PartType type, float x, float y, float z, BindingSpec binding) {
            movables.add(new MovablePart(type, new Matrix4().setToTranslation(x, y, z), binding));
            return this;
        }

        /** Adds a box loaded from a model JSON: geometry + per-face sprite names, no paint (runtime path). */
        Builder loadedBox(float cx, float cy, float cz, float sx, float sy, float sz, String[] faceSprites) {
            return box(PartMesh.Box.loaded(cx, cy, cz, sx, sy, sz, faceSprites));
        }

        /** Adds a pre-built box (used by {@link ModelLoader}). */
        Builder box(PartMesh.Box b) {
            statics.add(b);
            return this;
        }

        ComponentModel build() {
            return new ComponentModel(id, List.copyOf(statics), List.copyOf(movables));
        }
    }
}
