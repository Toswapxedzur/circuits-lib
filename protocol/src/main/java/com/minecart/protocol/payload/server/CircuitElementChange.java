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
 * One ordered step in a {@link CircuitElementPayload}: insert a serialized element, remove by id, or apply a
 * sync delta to an existing element. Insert/change steps carry the {@link com.minecart.registry.CircuitElementType} id.
 *
 * <h2>Rebind via {@code sourceCircuitId}</h2>
 * A {@link Kind#CHANGE} op may carry an optional {@link #sourceCircuitId()}. When set, it means: the element
 * previously lived in {@code sourceCircuitId} and has been moved to the payload's outer circuit. The client
 * pulls the element out of the source circuit's collections and drops it into the destination before
 * applying any sync data. Used by the server to mirror the silent circuit rebinds that happen during
 * {@code Circuit.mergeInto} (edge insertion fuses two circuits) and {@code Circuit.seperate} (edge removal
 * cleaves one).
 *
 * <h2>Nullable {@code data} on {@link Kind#CHANGE}</h2>
 * For a {@code CHANGE}, {@code data} may be {@code null}, which means "no element-info change to apply — the
 * op is structural only". This is the natural form for pure rebinds. {@code INSERT} always requires non-null
 * {@code data}; {@code REMOVE} always carries {@code null} {@code data}.
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
    /**
     * Element-info payload. Required for {@link Kind#INSERT}; must be {@code null} for {@link Kind#REMOVE};
     * optional (may be {@code null}) for {@link Kind#CHANGE} — {@code null} means structural-only / rebind
     * with no info change to apply on the client.
     */
    private final CompoundTag data;
    /**
     * Optional source circuit id for {@link Kind#CHANGE} ops; non-null marks a rebind from {@code sourceCircuitId}
     * to the payload's outer circuit. Must be {@code null} for {@link Kind#INSERT} and {@link Kind#REMOVE}.
     */
    private final UUID sourceCircuitId;

    private CircuitElementChange(Kind kind, String registryTypeId, UUID elementId,
                                 CompoundTag data, UUID sourceCircuitId) {
        validate(kind, registryTypeId, elementId, data, sourceCircuitId);
        this.kind = kind;
        this.registryTypeId = registryTypeId;
        this.elementId = elementId;
        this.data = data;
        this.sourceCircuitId = sourceCircuitId;
    }

    private static void validate(Kind kind, String registryTypeId, UUID elementId,
                                 CompoundTag data, UUID sourceCircuitId) {
        if (kind == null) {
            throw new IllegalArgumentException("kind is required");
        }
        switch (kind) {
            case INSERT -> {
                if (data == null) throw new IllegalArgumentException("INSERT requires data");
                if (registryTypeId == null || registryTypeId.isEmpty()) {
                    throw new IllegalArgumentException("INSERT requires registryTypeId");
                }
                if (sourceCircuitId != null) {
                    throw new IllegalArgumentException("INSERT cannot carry sourceCircuitId");
                }
            }
            case REMOVE -> {
                if (elementId == null) throw new IllegalArgumentException("REMOVE requires elementId");
                if (data != null) throw new IllegalArgumentException("REMOVE cannot carry data");
                if (sourceCircuitId != null) {
                    throw new IllegalArgumentException("REMOVE cannot carry sourceCircuitId");
                }
            }
            case CHANGE -> {
                if (elementId == null) throw new IllegalArgumentException("CHANGE requires elementId");
                if (registryTypeId == null || registryTypeId.isEmpty()) {
                    throw new IllegalArgumentException("CHANGE requires registryTypeId");
                }
                if (data == null && sourceCircuitId == null) {
                    throw new IllegalArgumentException(
                            "CHANGE must carry data and/or sourceCircuitId; otherwise it is a no-op");
                }
            }
        }
    }

    public static CircuitElementChange insert(String registryTypeId, CompoundTag data) {
        return new CircuitElementChange(Kind.INSERT, registryTypeId, null, data, null);
    }

    public static CircuitElementChange remove(UUID elementId) {
        return new CircuitElementChange(Kind.REMOVE, null, elementId, null, null);
    }

    /**
     * Incremental sync without rebind: {@code data} should match what {@link SyncRegistry#writeSyncData}
     * writes for that element type.
     */
    public static CircuitElementChange change(String registryTypeId, UUID elementId, CompoundTag syncData) {
        return new CircuitElementChange(Kind.CHANGE, registryTypeId, elementId, syncData, null);
    }

    /**
     * Rebind-with-optional-sync: move the element from {@code sourceCircuitId} into the payload's outer
     * circuit before applying any {@code syncData}. {@code syncData} may be {@code null} for a pure rebind.
     */
    public static CircuitElementChange changeWithRebind(String registryTypeId, UUID elementId,
                                                        CompoundTag syncData, UUID sourceCircuitId) {
        if (sourceCircuitId == null) {
            throw new IllegalArgumentException("changeWithRebind requires sourceCircuitId");
        }
        return new CircuitElementChange(Kind.CHANGE, registryTypeId, elementId, syncData, sourceCircuitId);
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
     * Builds a structural-only rebind step for {@code element} moving from {@code sourceCircuitId} to the
     * payload's outer circuit. Carries no {@code data} — the client only moves the element between circuit
     * mirrors and does not touch its info.
     */
    public static CircuitElementChange rebindOnly(CircuitElement element, UUID sourceCircuitId) {
        return changeWithRebind(registryTypeIdOf(element), element.getId(), null, sourceCircuitId);
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

    public UUID sourceCircuitId() {
        return sourceCircuitId;
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
                if (data != null) {
                    tag.put(ProtocolStrings.ELEMENT_TAG_DATA, data.copy());
                }
                if (sourceCircuitId != null) {
                    TagUtil.putUUID(tag, ProtocolStrings.TAG_SOURCE_CIRCUIT_ID, sourceCircuitId);
                }
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
                CompoundTag data = null;
                if (tag.get(ProtocolStrings.ELEMENT_TAG_DATA) != null) {
                    data = TagUtil.requireCompoundTag(
                            tag.get(ProtocolStrings.ELEMENT_TAG_DATA), ProtocolStrings.ELEMENT_TAG_DATA);
                }
                UUID sourceCircuitId = TagUtil.getUUID(tag, ProtocolStrings.TAG_SOURCE_CIRCUIT_ID);
                if (regId == null || regId.isEmpty()) {
                    regId = data != null ? data.getString(CoreStrings.ELEMENT_TYPE) : null;
                }
                if (regId == null || regId.isEmpty()) {
                    throw new IllegalArgumentException("Missing element registry type id");
                }
                if (data == null && sourceCircuitId == null) {
                    throw new IllegalArgumentException(
                            "CHANGE must carry data and/or sourceCircuitId; otherwise it is a no-op");
                }
                yield new CircuitElementChange(Kind.CHANGE, regId, id, data, sourceCircuitId);
            }
        };
    }
}
