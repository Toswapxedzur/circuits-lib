package com.minecart.logic;

import com.minecart.math.DoubleVar;
import com.minecart.math.LinearSystem;
import com.minecart.registry.AllComponents;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;

import java.util.Comparator;
import java.util.Set;
import java.util.UUID;

public abstract class CircuitElement implements Comparable<CircuitElement> {
    public static final Comparator<? extends CircuitElement> comparator = (f, s) -> f.id.compareTo(s.id);

    protected UUID id;
    protected World world;
    protected Circuit circuit;

    /** Set when created via {@link com.minecart.registry.CircuitElementType#create}; used for save/load. */
    protected String registryTypeId;

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

    protected void saveElementHeader(CompoundTag tag) {
        TagUtil.putUUID(tag, "id", getId());
        tag.putString("type", typeIdForSave());
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
        return o.id.compareTo(this.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
