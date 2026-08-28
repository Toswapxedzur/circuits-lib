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
    List<Movable> movables = new ArrayList<>();

    /** One axis-aligned cuboid: object-space {@code from}/{@code to} corners + a sprite per present face. */
    static final class Element {
        float[] from;                      // [x,y,z] min corner (object space)
        float[] to;                        // [x,y,z] max corner
        Map<String, String> faces;         // faceKey → sprite name (only non-degenerate faces present)
    }

    /** A movable sub-part: which part-type it borrows, where it sits, and its serialised binding. */
    static final class Movable {
        String part;                       // part-type model id (a borrow / dependency)
        float[] at;                        // local translation [x,y,z]
        String bindingType;                // BindingSpec.type ("translate")
        String channel;
        float[] axis;
    }

    static int faceId(String key) {
        for (int f = 0; f < FACE_KEY.length; f++) if (FACE_KEY[f].equals(key)) return f;
        throw new IllegalArgumentException("Unknown face key: " + key);
    }

    /** Serialises a component model (or part-type, movables empty) to its JSON form. */
    static ModelJson of(String id, List<PartMesh.Box> boxes, List<ComponentModel.MovablePart> movs,
                        Map<PartType, String> typeIds) {
        ModelJson j = new ModelJson();
        j.id = id;
        for (PartMesh.Box b : boxes) {
            Element e = new Element();
            e.from = new float[]{b.cx() - b.sx() / 2f, b.cy() - b.sy() / 2f, b.cz() - b.sz() / 2f};
            e.to = new float[]{b.cx() + b.sx() / 2f, b.cy() + b.sy() / 2f, b.cz() + b.sz() / 2f};
            e.faces = new LinkedHashMap<>();
            for (int f = 0; f < 6; f++) {
                int[] wh = PaletteDither.size(b, f);
                if (wh[0] > 0 && wh[1] > 0) e.faces.put(FACE_KEY[f], b.faceSprite(f)); // = faceName(paint)
            }
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
            j.movables.add(mv);
        }
        return j;
    }

    /** Every sprite name any element references — the texture dependencies of this model. */
    List<String> textureDeps() {
        List<String> out = new ArrayList<>();
        for (Element e : elements) if (e.faces != null) out.addAll(e.faces.values());
        return out;
    }

    /** Every part-type id the movables borrow — the model dependencies of this model. */
    List<String> partDeps() {
        List<String> out = new ArrayList<>();
        for (Movable m : movables) out.add(m.part);
        return out;
    }
}
