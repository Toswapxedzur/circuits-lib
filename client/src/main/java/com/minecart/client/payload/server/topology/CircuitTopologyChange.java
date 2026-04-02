package com.minecart.client.payload.server.topology;

import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;

import java.util.Objects;
import java.util.UUID;

/**
 * One ordered step in a {@link CircuitTopologyPayload}: insert a serialized element or remove by id.
 */
public final class CircuitTopologyChange {

    public static final String TAG_KIND = "kind";
    public static final String TAG_ELEMENT_KIND = "element_kind";
    public static final String TAG_ELEMENT_ID = "element_id";
    public static final String TAG_DATA = "data";

    public static final String KIND_INSERT = "insert";
    public static final String KIND_REMOVE = "remove";

    public static final String ELEM_NODE = "node";
    public static final String ELEM_EDGE = "edge";
    public static final String ELEM_COMPONENT = "component";

    public enum Kind {
        INSERT,
        REMOVE
    }

    public enum ElementKind {
        NODE,
        EDGE,
        COMPONENT
    }

    private final Kind kind;
    private final ElementKind elementKind;
    private final UUID elementId;
    private final CompoundTag data;

    private CircuitTopologyChange(Kind kind, ElementKind elementKind, UUID elementId, CompoundTag data) {
        this.kind = kind;
        this.elementKind = elementKind;
        this.elementId = elementId;
        this.data = data;
    }

    public static CircuitTopologyChange insert(ElementKind elementKind, CompoundTag data) {
        Objects.requireNonNull(elementKind, "elementKind");
        Objects.requireNonNull(data, "data");
        return new CircuitTopologyChange(Kind.INSERT, elementKind, null, data);
    }

    public static CircuitTopologyChange remove(UUID elementId) {
        Objects.requireNonNull(elementId, "elementId");
        return new CircuitTopologyChange(Kind.REMOVE, null, elementId, null);
    }

    public Kind kind() {
        return kind;
    }

    public ElementKind elementKind() {
        return elementKind;
    }

    public UUID elementId() {
        return elementId;
    }

    public CompoundTag data() {
        return data;
    }

    public void save(CompoundTag tag) {
        if (kind == Kind.INSERT) {
            tag.putString(TAG_KIND, KIND_INSERT);
            tag.putString(TAG_ELEMENT_KIND, elementKindToString(elementKind));
            tag.put(TAG_DATA, data.copy());
        } else {
            tag.putString(TAG_KIND, KIND_REMOVE);
            TagUtil.putUUID(tag, TAG_ELEMENT_ID, elementId);
        }
    }

    public static CircuitTopologyChange load(CompoundTag tag) {
        String k = tag.getString(TAG_KIND);
        if (KIND_INSERT.equals(k)) {
            String ek = tag.getString(TAG_ELEMENT_KIND);
            ElementKind elementKind = parseElementKind(ek);
            CompoundTag data = TagUtil.requireCompoundTag(tag.get(TAG_DATA), TAG_DATA);
            return insert(elementKind, data);
        }
        if (KIND_REMOVE.equals(k)) {
            UUID id = TagUtil.getUUID(tag, TAG_ELEMENT_ID);
            if (id == null) {
                throw new IllegalArgumentException("Missing '" + TAG_ELEMENT_ID + "' for remove step");
            }
            return remove(id);
        }
        throw new IllegalArgumentException("Unknown topology step kind: " + k);
    }

    private static String elementKindToString(ElementKind ek) {
        return switch (ek) {
            case NODE -> ELEM_NODE;
            case EDGE -> ELEM_EDGE;
            case COMPONENT -> ELEM_COMPONENT;
        };
    }

    private static ElementKind parseElementKind(String s) {
        if (ELEM_NODE.equals(s)) {
            return ElementKind.NODE;
        }
        if (ELEM_EDGE.equals(s)) {
            return ElementKind.EDGE;
        }
        if (ELEM_COMPONENT.equals(s)) {
            return ElementKind.COMPONENT;
        }
        throw new IllegalArgumentException("Unknown element_kind: " + s);
    }
}
