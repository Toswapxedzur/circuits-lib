package com.minecart.display.render.engine;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;

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

    /** The part's single <b>axis-aligned</b> collision box (object space): centre + half-extents, a default
     *  material, and optional per-face overrides. Generated at datagen (see {@link ModelJson.Collision}); the
     *  physics turns it into a {@code btBoxShape}. May be null for a geometry-less model. */
    record Collision(float cx, float cy, float cz, float hx, float hy, float hz,
                     float friction, float restitution, FaceMaterial[] faces) {}

    /** A per-face collision-material override (index = faceId 0..5). Null fields fall back to the box defaults. */
    record FaceMaterial(Float friction, Float restitution, Boolean solid) {}

    /**
     * A physical <b>connector</b> (object space) — a stud/socket where this part mates another (see the physical
     * free-placement system). {@code local} is its position, {@code axis} the mating direction (a MALE stud points
     * OUT along +axis; a FEMALE socket receives along −axis), {@code terminal} which of the part's electrical
     * terminals it carries (0 = A/−, 1 = B/+). {@code male} true = stud, false = socket. (Polarity/keying: later.)
     */
    record Connector(Vector3 local, Vector3 axis, int terminal, boolean male) {}

    final String id;
    final List<PartMesh.Box> staticBoxes;
    final List<PartMesh.Quad> staticQuads;
    final List<MovablePart> movableParts;
    final Collision collision;                  // the axis-aligned collision box (may be null)
    final List<Connector> connectors;           // physical mating points (may be empty)

    private ComponentModel(String id, List<PartMesh.Box> staticBoxes, List<PartMesh.Quad> staticQuads,
                           List<MovablePart> movableParts, Collision collision, List<Connector> connectors) {
        this.id = id;
        this.staticBoxes = staticBoxes;
        this.staticQuads = staticQuads;
        this.movableParts = movableParts;
        this.collision = collision;
        this.connectors = connectors;
    }

    static Builder of(String id) {
        return new Builder(id);
    }

    static final class Builder {
        private final String id;
        private final List<PartMesh.Box> statics = new ArrayList<>();
        private final List<PartMesh.Quad> quads = new ArrayList<>();
        private final List<MovablePart> movables = new ArrayList<>();
        private final List<Connector> connectors = new ArrayList<>();
        private Collision collision;

        private Builder(String id) {
            this.id = id;
        }

        /** Sets the loaded axis-aligned collision box (ModelLoader passes the datagen'd one). */
        Builder collision(Collision c) {
            this.collision = c;
            return this;
        }

        /** Adds a physical connector (object space). */
        Builder connector(Connector c) {
            connectors.add(c);
            return this;
        }

        Builder box(float cx, float cy, float cz, float sx, float sy, float sz, PaletteDither.Paint paint) {
            statics.add(PartMesh.Box.local(cx, cy, cz, sx, sy, sz, paint));
            return this;
        }

        /** A box that is TINTED by the component-entity colour and/or drawn TRANSLUCENT (e.g. the LED cores). */
        Builder box(float cx, float cy, float cz, float sx, float sy, float sz, PaletteDither.Paint paint,
                    boolean tint, boolean translucent) {
            statics.add(PartMesh.Box.local(cx, cy, cz, sx, sy, sz, paint, tint, translucent));
            return this;
        }

        /** A box with a TRACE decal printed on its top (+Y) face (e.g. a base top rim / a switch top strip). */
        Builder box(float cx, float cy, float cz, float sx, float sy, float sz, PaletteDither.Paint paint,
                    PartMesh.Trace trace) {
            statics.add(PartMesh.Box.local(cx, cy, cz, sx, sy, sz, paint, trace));
            return this;
        }

        /** A box whose object-space shading centre differs from where it sits (e.g. a movable part's rest pose). */
        Builder boxAt(float cx, float cy, float cz, float sx, float sy, float sz, PaletteDither.Paint paint,
                      float ocx, float ocy, float ocz) {
            statics.add(new PartMesh.Box(cx, cy, cz, sx, sy, sz, paint, ocx, ocy, ocz, null,
                    false, false, PartMesh.WHITE_BITS, null));
            return this;
        }

        Builder movable(PartType type, float x, float y, float z, BindingSpec binding) {
            movables.add(new MovablePart(type, new Matrix4().setToTranslation(x, y, z), binding));
            return this;
        }

        /** Adds a box loaded from a model JSON: geometry + per-face sprite names, no paint (runtime path). */
        Builder loadedBox(float cx, float cy, float cz, float sx, float sy, float sz, String[] faceSprites) {
            return box(PartMesh.Box.loaded(cx, cy, cz, sx, sy, sz, faceSprites, false, false));
        }

        /** Adds a pre-built box (used by {@link ModelLoader}). */
        Builder box(PartMesh.Box b) {
            statics.add(b);
            return this;
        }

        /** Adds an oriented (tilted) flat quad — corners p00,p10,p11,p01, sprite drawn from {@code paint}. */
        Builder quad(Vector3 p00, Vector3 p10, Vector3 p11, Vector3 p01, PaletteDither.Paint paint, int pw, int ph) {
            quads.add(PartMesh.Quad.local(p00, p10, p11, p01, paint, pw, ph));
            return this;
        }

        /** Adds a pre-built quad (used by {@link ModelLoader}). */
        Builder quad(PartMesh.Quad q) {
            quads.add(q);
            return this;
        }

        ComponentModel build() {
            return new ComponentModel(id, List.copyOf(statics), List.copyOf(quads), List.copyOf(movables),
                    collision, List.copyOf(connectors));
        }
    }
}
