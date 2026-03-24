package com.minecart.logic;

import com.minecart.math.function.DoubleVar;
import com.minecart.math.function.LinearSystem;

import java.util.Comparator;
import java.util.Set;
import java.util.UUID;

public abstract class CircuitElement implements Comparable<CircuitElement> {
    public static final Comparator<? extends CircuitElement> comparator = (f, s) -> f.id.compareTo(s.id);

    protected UUID id;
    protected World world;
    protected Circuit circuit;

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
