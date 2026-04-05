package com.minecart.client.payload.server.topology;

import com.minecart.client.ClientStrings;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;

import java.util.Objects;
import java.util.UUID;

/**
 * One ordered step in a {@link CircuitTopologyPayload}: insert a serialized element or remove by id.
 */
public final class CircuitTopologyChange {

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
            tag.putString(ClientStrings.TAG_KIND, ClientStrings.KIND_INSERT);
            tag.putString(ClientStrings.TOPOLOGY_TAG_ELEMENT_KIND, elementKindToString(elementKind));
            tag.put(ClientStrings.TOPOLOGY_TAG_DATA, data.copy());
        } else {
            tag.putString(ClientStrings.TAG_KIND, ClientStrings.KIND_REMOVE);
            TagUtil.putUUID(tag, ClientStrings.TAG_ELEMENT_ID, elementId);
        }
    }

    public static CircuitTopologyChange load(CompoundTag tag) {
        String k = tag.getString(ClientStrings.TAG_KIND);
        if (ClientStrings.KIND_INSERT.equals(k)) {
            String ek = tag.getString(ClientStrings.TOPOLOGY_TAG_ELEMENT_KIND);
            ElementKind elementKind = parseElementKind(ek);
            CompoundTag data = TagUtil.requireCompoundTag(tag.get(ClientStrings.TOPOLOGY_TAG_DATA), ClientStrings.TOPOLOGY_TAG_DATA);
            return insert(elementKind, data);
        }
        if (ClientStrings.KIND_REMOVE.equals(k)) {
            UUID id = TagUtil.getUUID(tag, ClientStrings.TAG_ELEMENT_ID);
            if (id == null) {
                throw new IllegalArgumentException("Missing '" + ClientStrings.TAG_ELEMENT_ID + "' for remove step");
            }
            return remove(id);
        }
        throw new IllegalArgumentException("Unknown topology step kind: " + k);
    }

    private static String elementKindToString(ElementKind ek) {
        return switch (ek) {
            case NODE -> ClientStrings.ELEM_NODE;
            case EDGE -> ClientStrings.ELEM_EDGE;
            case COMPONENT -> ClientStrings.ELEM_COMPONENT;
        };
    }

    private static ElementKind parseElementKind(String s) {
        if (ClientStrings.ELEM_NODE.equals(s)) {
            return ElementKind.NODE;
        }
        if (ClientStrings.ELEM_EDGE.equals(s)) {
            return ElementKind.EDGE;
        }
        if (ClientStrings.ELEM_COMPONENT.equals(s)) {
            return ElementKind.COMPONENT;
        }
        throw new IllegalArgumentException("Unknown element_kind: " + s);
    }
}
