package com.minecart.display.render.engine;

import com.badlogic.gdx.math.Matrix4;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The on-disk shape of a part model — a <b>Minecraft-style</b> model JSON (Gson-mapped). A model is a list of
 * axis-aligned {@link Element}s (a cuboid {@code from}→{@code to} with a per-face sprite reference), an optional
 * {@code parent} to inherit elements from (a "borrow"), and any {@link Movable} sub-parts. The per-face art is
 * kept the project's way: each face names its own baked sprite PNG (see {@link SeedPartTextures} / the atlas),
 * so the runtime is still a pure sampler. Written by {@code GenModels}, read by {@link ModelLoader}.
 */
final class ModelJson {

    /** faceId → JSON face key (Minecraft names): 0 +X, 1 −X, 2 +Y, 3 −Y, 4 +Z, 5 −Z. */
    static final String[] FACE_KEY = {"east", "west", "up", "down", "south", "north"};

    String id;
    String parent;                         // optional: inherit this model's elements first (borrow)
    List<Element> elements = new ArrayList<>();
    List<Quad> quads = new ArrayList<>();   // oriented (tilted) flat plates — non-axis-aligned
    List<Movable> movables = new ArrayList<>();
    Collision collision;                    // a single axis-aligned collision box (physics); generated separately

    /**
     * The part's <b>collision</b> shape: a single <b>axis-aligned</b> box over the model's visible extent — the
     * cheapest accurate Bullet collider (box-box SAT, no compound/hull cost), ≈ the appearance. Carries a default
     * material and optional per-face overrides (the "configurable per-face collision material" feature; default =
     * all faces rigid, i.e. the box-level material). Generated at datagen from the geometry (owner: bbox generated
     * SEPARATELY, axis-aligned), so the runtime never derives it live.
     */
    static final class Collision {
        float[] from;                      // AABB min corner (object space)
        float[] to;                        // AABB max corner
        float friction = 0.9f;             // default surface friction
        float restitution = 0f;            // default bounciness (0 = no bounce)
        Face[] faces;                      // optional per-face overrides, index = faceId (0..5); null = all rigid
    }

    /** A per-face collision-material override (null fields fall back to the box defaults). */
    static final class Face {
        Float friction;
        Float restitution;
        Boolean solid;                     // false = that face doesn't collide (pass-through)
    }

    /** One axis-aligned cuboid: object-space {@code from}/{@code to} corners + a sprite per present face. */
    static final class Element {
        float[] from;                      // [x,y,z] min corner (object space)
        float[] to;                        // [x,y,z] max corner
        Map<String, String> faces;         // faceKey → sprite name (only non-degenerate faces present)
        boolean tint;                      // multiply by the component-entity colour (greyscale → colour)
        boolean translucent;               // draw in the blended pass (glass-like)
    }

    /** An oriented flat quad: 4 object-space corners (p00,p10,p11,p01), its sprite, and the sprite's texel size. */
    static final class Quad {
        float[][] corners;                 // [4][3] — p00,p10,p11,p01
        int w, h;                          // sprite size in texels
        String sprite;
    }

    /** A movable sub-part: which part-type it borrows, where it sits, and its serialised binding. */
    static final class Movable {
        String part;                       // part-type model id (a borrow / dependency)
        float[] at;                        // local translation [x,y,z]
        String bindingType;                // BindingSpec.type ("translate" | "rotate")
        String channel;
        float[] axis;
        float[] pivot;                     // rotate only: pivot point [x,y,z] (null for translate)
        float degPerUnit;                  // rotate only: degrees per channel unit (0 for translate)
    }

    static int faceId(String key) {
        for (int f = 0; f < FACE_KEY.length; f++) if (FACE_KEY[f].equals(key)) return f;
        throw new IllegalArgumentException("Unknown face key: " + key);
    }

    /** Serialises a component model (or part-type, movables empty) to its JSON form. */
    static ModelJson of(String id, List<PartMesh.Box> boxes, List<PartMesh.Quad> quads,
                        List<ComponentModel.MovablePart> movs, Map<PartType, String> typeIds) {
        ModelJson j = new ModelJson();
        j.id = id;
        for (PartMesh.Quad q : quads) {
            Quad jq = new Quad();
            jq.corners = new float[][]{
                    {q.o00().x, q.o00().y, q.o00().z}, {q.o10().x, q.o10().y, q.o10().z},
                    {q.o11().x, q.o11().y, q.o11().z}, {q.o01().x, q.o01().y, q.o01().z}};
            jq.w = q.pw();
            jq.h = q.ph();
            jq.sprite = q.sprite();
            j.quads.add(jq);
        }
        for (PartMesh.Box b : boxes) {
            Element e = new Element();
            e.from = new float[]{b.cx() - b.sx() / 2f, b.cy() - b.sy() / 2f, b.cz() - b.sz() / 2f};
            e.to = new float[]{b.cx() + b.sx() / 2f, b.cy() + b.sy() / 2f, b.cz() + b.sz() / 2f};
            e.faces = new LinkedHashMap<>();
            for (int f = 0; f < 6; f++) {
                int[] wh = PaletteDither.size(b, f);
                if (wh[0] > 0 && wh[1] > 0) e.faces.put(FACE_KEY[f], b.faceSprite(f)); // = faceName(paint)
            }
            e.tint = b.tint();
            e.translucent = b.translucent();
            j.elements.add(e);
        }
        for (ComponentModel.MovablePart m : movs) {
            Movable mv = new Movable();
            mv.part = typeIds.get(m.type());
            Matrix4 l = m.local();
            mv.at = new float[]{l.val[Matrix4.M03], l.val[Matrix4.M13], l.val[Matrix4.M23]};
            mv.bindingType = m.binding().type();
            mv.channel = m.binding().channel();
            mv.axis = m.binding().axis();
            mv.pivot = m.binding().pivot();
            mv.degPerUnit = m.binding().degPerUnit();
            j.movables.add(mv);
        }
        j.collision = aabb(boxes, quads); // the single axis-aligned collision box over the visible extent
        return j;
    }

    /** The tight axis-aligned box over every box + quad corner (object space), or null if the model is empty. */
    private static Collision aabb(List<PartMesh.Box> boxes, List<PartMesh.Quad> quads) {
        float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        boolean any = false;
        for (PartMesh.Box b : boxes) {
            any = true;
            min[0] = Math.min(min[0], b.cx() - b.sx() / 2f); max[0] = Math.max(max[0], b.cx() + b.sx() / 2f);
            min[1] = Math.min(min[1], b.cy() - b.sy() / 2f); max[1] = Math.max(max[1], b.cy() + b.sy() / 2f);
            min[2] = Math.min(min[2], b.cz() - b.sz() / 2f); max[2] = Math.max(max[2], b.cz() + b.sz() / 2f);
        }
        for (PartMesh.Quad q : quads) {
            for (com.badlogic.gdx.math.Vector3 c : new com.badlogic.gdx.math.Vector3[]{q.o00(), q.o10(), q.o11(), q.o01()}) {
                any = true;
                min[0] = Math.min(min[0], c.x); max[0] = Math.max(max[0], c.x);
                min[1] = Math.min(min[1], c.y); max[1] = Math.max(max[1], c.y);
                min[2] = Math.min(min[2], c.z); max[2] = Math.max(max[2], c.z);
            }
        }
        if (!any) return null;
        Collision col = new Collision();
        col.from = min;
        col.to = max;
        return col;
    }

    /** Every sprite name any element or quad references — the texture dependencies of this model. */
    List<String> textureDeps() {
        List<String> out = new ArrayList<>();
        for (Element e : elements) if (e.faces != null) out.addAll(e.faces.values());
        for (Quad q : quads) if (q.sprite != null) out.add(q.sprite);
        return out;
    }

    /** Every part-type id the movables borrow — the model dependencies of this model. */
    List<String> partDeps() {
        List<String> out = new ArrayList<>();
        for (Movable m : movables) out.add(m.part);
        return out;
    }
}
