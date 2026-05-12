package com.minecart.protocol.payload.server;

import com.minecart.logic.CircuitElement;
import com.minecart.misc.CoreStrings;
import com.minecart.protocol.misc.ProtocolStrings;
import com.minecart.protocol.sync.SyncRegistry;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One ordered step in a {@link CircuitElementPayload}: insert a serialized element, remove by id, or apply a sync delta
 * to an existing element. Insert/change steps carry the {@link com.minecart.registry.CircuitElementType} id.
 */
public final class CircuitElementChange {

    public enum Kind {
        INSERT("insert"),
        REMOVE("remove"),
        CHANGE("change");

        private static final Map<String, Kind> TABLE = new HashMap<>();
        String value;
        Kind(String value){
            this.value = value;
        }

        static {
            for(Kind kind : Kind.values()){
                TABLE.put(kind.value, kind);
            }
        }
    }

    private final Kind kind;
    /** Registry type id for INSERT/CHANGE; {@code null} for REMOVE. */
    private final String registryTypeId;
    private final UUID elementId;
    private final CompoundTag data;

    private CircuitElementChange(Kind kind, String registryTypeId, UUID elementId, CompoundTag data) {
        this.kind = kind;
        this.registryTypeId = registryTypeId;
        this.elementId = elementId;
        this.data = data;
    }

    public static CircuitElementChange insert(String registryTypeId, CompoundTag data) {
        return new CircuitElementChange(Kind.INSERT, registryTypeId, null, data);
    }

    public static CircuitElementChange remove(UUID elementId) {
        return new CircuitElementChange(Kind.REMOVE, null, elementId, null);
    }

    /**
     * Incremental sync: {@code data} should match what {@link SyncRegistry#writeSyncData} writes for that element type.
     */
    public static CircuitElementChange change(String registryTypeId, UUID elementId, CompoundTag syncData) {
        return new CircuitElementChange(Kind.CHANGE, registryTypeId, elementId, syncData);
    }

    /**
     * Builds a {@link Kind#CHANGE} step using {@link SyncRegistry#writeSyncData} and the element's registry type id.
     */
    public static CircuitElementChange changeFromSync(CircuitElement element) {
        CompoundTag tag = new CompoundTag();
        SyncRegistry.writeSyncData(element, tag);
        return change(registryTypeIdOf(element), element.getId(), tag);
    }

    /**
     * Registry type id string for {@code el} (same key as {@link CoreStrings#ELEMENT_TYPE} in serialized element tags).
     */
    public static String registryTypeIdOf(CircuitElement el) {
        return CircuitElement.serialize(el).getString(CoreStrings.ELEMENT_TYPE);
    }

    public Kind kind() {
        return kind;
    }

    public String registryTypeId() {
        return registryTypeId;
    }

    public UUID elementId() {
        return elementId;
    }

    public CompoundTag data() {
        return data;
    }

    public void save(CompoundTag tag) {
        tag.putString(ProtocolStrings.TAG_KIND, kind.value);
        switch (kind) {
            case INSERT -> {
                tag.putString(ProtocolStrings.ELEMENT_TAG_ELEMENT_REGISTRY_ID, registryTypeId);
                tag.put(ProtocolStrings.ELEMENT_TAG_DATA, data.copy());
            }
            case REMOVE -> TagUtil.putUUID(tag, ProtocolStrings.TAG_ELEMENT_ID, elementId);
            case CHANGE -> {
                tag.putString(ProtocolStrings.ELEMENT_TAG_ELEMENT_REGISTRY_ID, registryTypeId);
                TagUtil.putUUID(tag, ProtocolStrings.TAG_ELEMENT_ID, elementId);
                tag.put(ProtocolStrings.ELEMENT_TAG_DATA, data.copy());
            }
        }
    }

    public static CircuitElementChange load(CompoundTag tag) {
        Kind k = Kind.TABLE.get(tag.getString(ProtocolStrings.TAG_KIND));
        if (k == null) {
            throw new IllegalArgumentException("Unknown kind: " + tag.getString(ProtocolStrings.TAG_KIND));
        }
        return switch (k) {
            case INSERT -> {
                CompoundTag data = TagUtil.requireCompoundTag(tag.get(ProtocolStrings.ELEMENT_TAG_DATA), ProtocolStrings.ELEMENT_TAG_DATA);
                String regId = tag.getString(ProtocolStrings.ELEMENT_TAG_ELEMENT_REGISTRY_ID);
                if (regId == null || regId.isEmpty()) {
                    regId = data.getString(CoreStrings.ELEMENT_TYPE);
                }
                if (regId == null || regId.isEmpty()) {
                    throw new IllegalArgumentException("Missing element registry type id");
                }
                yield insert(regId, data);
            }
            case REMOVE -> {
                UUID id = TagUtil.getUUID(tag, ProtocolStrings.TAG_ELEMENT_ID);
                if (id == null) {
                    throw new IllegalArgumentException("Missing element_id");
                }
                yield remove(id);
            }
            case CHANGE -> {
                String regId = tag.getString(ProtocolStrings.ELEMENT_TAG_ELEMENT_REGISTRY_ID);
                UUID id = TagUtil.getUUID(tag, ProtocolStrings.TAG_ELEMENT_ID);
                if (id == null) {
                    throw new IllegalArgumentException("Missing element_id");
                }
                CompoundTag data = TagUtil.requireCompoundTag(tag.get(ProtocolStrings.ELEMENT_TAG_DATA), ProtocolStrings.ELEMENT_TAG_DATA);
                if (regId == null || regId.isEmpty()) {
                    regId = data.getString(CoreStrings.ELEMENT_TYPE);
                }
                if (regId == null || regId.isEmpty()) {
                    throw new IllegalArgumentException("Missing element registry type id");
                }
                yield change(regId, id, data);
            }
        };
    }
}
