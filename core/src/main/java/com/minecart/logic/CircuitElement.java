package com.minecart.logic;

import com.minecart.misc.CoreStrings;
import com.minecart.foundation.Circuit;
import com.minecart.foundation.World;
import com.minecart.math.DoubleVar;
import com.minecart.math.LinearSystem;
import com.minecart.registry.AllComponents;
import com.minecart.registry.CircuitElementRegistry;
import com.minecart.registry.CircuitElementType;
import com.minecart.registry.ElementInfoRegistry;
import com.minecart.registry.ElementInfoType;
import com.minecart.serialization.TagSerializable;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;
import com.minecart.serialization.tag.Tag;
import com.minecart.variant.ElementInfo;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public sealed class CircuitElement implements Comparable<CircuitElement>, TagSerializable
        permits CircuitNode, CircuitEdge, CircuitComponent {
    public static final Comparator<? extends CircuitElement> comparator = (f, s) -> f.id.compareTo(s.id);

    protected UUID id;
    protected World world;
    protected Circuit circuit;

    /** Set when created via {@link com.minecart.registry.CircuitElementType#create}; used for save/load. */
    protected String registryTypeId;

    /**
     * Per-element metadata bag attached via {@link com.minecart.event.events.ElementInfoInjectEvent}.
     * Insertion-ordered so save output is stable. At most one entry per {@link ElementInfoType} key
     * (data-component semantics).
     */
    protected final Map<ElementInfoType<?>, ElementInfo> infos = new LinkedHashMap<>();

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getRegistryTypeId() {
        return registryTypeId;
    }

    public void setRegistryTypeId(String registryTypeId) {
        this.registryTypeId = registryTypeId;
    }

    /** Registry id string written to tags; override or use {@link com.minecart.registry.CircuitElementType#create}. */
    protected String typeIdForSave() {
        String t = getRegistryTypeId();
        if (t != null) {
            return t;
        }
        if (getClass() == CircuitNode.class) {
            return AllComponents.CONNECTION.getTypeId();
        }
        throw new IllegalStateException(
                "Cannot serialize " + getClass().getName() + " without registryTypeId; create via CircuitElementType or setRegistryTypeId");
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putString(CoreStrings.ELEMENT_TYPE, typeIdForSave());
        TagUtil.putUUID(tag, CoreStrings.ELEMENT_ID, getId());
        saveInfos(tag);
    }

    @Override
    public void load(CompoundTag tag) {
        setId(TagUtil.getUUID(tag, CoreStrings.ELEMENT_ID));
        loadInfos(tag);
    }

    /**
     * Writes a sub-{@link CompoundTag} under {@link CoreStrings#INFOS} containing every attached
     * {@link ElementInfo} whose {@link ElementInfo#shouldSerialize() shouldSerialize()} returns {@code true},
     * keyed by its {@link ElementInfoType#getTypeId() type id}. Skips writing the sub-tag entirely if no
     * info is serializable.
     */
    protected void saveInfos(CompoundTag tag) {
        if (infos.isEmpty()) {
            return;
        }
        CompoundTag infosTag = new CompoundTag();
        for (Map.Entry<ElementInfoType<?>, ElementInfo> e : infos.entrySet()) {
            ElementInfo info = e.getValue();
            if (info == null || !info.shouldSerialize()) {
                continue;
            }
            CompoundTag infoTag = new CompoundTag();
            info.save(infoTag);
            infosTag.put(e.getKey().getTypeId(), infoTag);
        }
        if (!infosTag.keySet().isEmpty()) {
            tag.put(CoreStrings.INFOS, infosTag);
        }
    }

    /**
     * Reads infos previously written by {@link #saveInfos}. Only overwrites infos whose type id is present
     * in the tag, so any defaults injected via {@link com.minecart.event.events.ElementInfoInjectEvent}
     * before load remain intact for non-persisted infos. Unknown type ids (contributing module not loaded)
     * are silently skipped.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    protected void loadInfos(CompoundTag tag) {
        Tag infosTag = tag.get(CoreStrings.INFOS);
        if (!(infosTag instanceof CompoundTag infosCompound)) {
            return;
        }
        for (String typeId : infosCompound.keySet()) {
            ElementInfoType type = ElementInfoRegistry.getType(typeId);
            if (type == null) {
                continue;
            }
            ElementInfo info = (ElementInfo) type.create();
            if (infosCompound.get(typeId) instanceof CompoundTag infoTag) {
                info.load(infoTag);
            }
            infos.put(type, info);
        }
    }

    /**
     * Doesn't save the world and level it belongs to
     */
    public static CompoundTag serialize(CircuitElement element){
        CompoundTag tag = new CompoundTag();
        // save() already writes ELEMENT_TYPE (via typeIdForSave); no need to write it twice.
        element.save(tag);
        return tag;
    }

    /**
     * Creates an element from registry data in {@code tag} (type + id) and {@code world}. Does not load
     * element-specific fields and does not register the element with a {@link Circuit} — that is
     * {@link Circuit}'s responsibility after {@link CircuitNode#load}, {@link CircuitEdge#load(CompoundTag, Circuit)},
     * or {@link CircuitComponent#load}.
     */
    public static CircuitElement deserialize(CompoundTag tag, World world) {
        UUID elementId = TagUtil.getUUID(tag, CoreStrings.ELEMENT_ID);
        if (elementId == null) {
            throw new IllegalArgumentException("Element missing id");
        }
        String typeId = tag.getString(CoreStrings.ELEMENT_TYPE);
        CircuitElementType<?> et = CircuitElementRegistry.getType(typeId);
        CircuitElement el = et.create(world, true);
        el.setId(elementId);
        return el;
    }

    /** @return the info attached under {@code type}, or {@code null} if none has been injected. */
    @SuppressWarnings("unchecked")
    public <I extends ElementInfo> I getInfo(ElementInfoType<I> type) {
        return (I) infos.get(type);
    }

    /**
     * Attaches {@code info} under {@code type}, replacing any existing entry. Pass {@code null} to remove.
     * Typically called via {@link com.minecart.event.events.ElementInfoInjectEvent#inject}.
     */
    public <I extends ElementInfo> void setInfo(ElementInfoType<I> type, I info) {
        if (info == null) {
            infos.remove(type);
        } else {
            infos.put(type, info);
        }
    }

    /** Read-only view of all attached infos, in insertion order. */
    public Map<ElementInfoType<?>, ElementInfo> getInfos() {
        return Collections.unmodifiableMap(infos);
    }

    public Circuit getCircuit() {
        return circuit;
    }

    public void setCircuit(Circuit circuit) {
        this.circuit = circuit;
    }

    public World getWorld() {
        return world;
    }

    public void setWorld(World world) {
        this.world = world;
    }

    /**
     * Notifies {@link Level} element-change listeners (registered during {@link Level#init()}). No-op if no world or
     * before {@link Level#init()} has run.
     */
    protected void notifyElementChanged() {
        World w = getWorld();
        if (w != null) {
            w.getLevel().notifyElementChanged(this);
        }
    }

    public void tick() {

    }

    public CircuitElement() {
        this.id = UUID.randomUUID();
    }

    /**
     * A set of relationship between different current and voltages that helps figure out the final current and voltages
     *
     * @param equations Append equation representing limitations by overriding this method
     */
    public void collectRule(LinearSystem.RelationProvider equations) {

    }

    /**
     * Collect all the variables
     *
     * @param variables All the data that could change and impacted by Rules
     */
    public void collectVariable(Set<DoubleVar> variables) {

    }

    @Override
    public int compareTo(CircuitElement o) {
        // Ascending by id, matching the declared {@link #comparator} (f.id.compareTo(s.id)).
        return this.id.compareTo(o.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
