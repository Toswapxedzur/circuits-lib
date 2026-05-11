package com.minecart.logic;

import com.minecart.misc.CoreStrings;
import com.minecart.foundation.Circuit;
import com.minecart.foundation.World;
import com.minecart.math.DoubleVar;
import com.minecart.math.LinearSystem;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;

import java.util.*;

public non-sealed class CircuitNode extends CircuitElement {
    protected DoubleVar voltage;
    protected Set<CircuitEdge> connection;
    protected CircuitComponent component;
    protected boolean ground;

    public CircuitNode(World world){
        setWorld(world);
        voltage = DoubleVar.create();
        connection = new LinkedHashSet<>();
    }

    @Override
    public void collectVariable(Set<DoubleVar> variables) {
        super.collectVariable(variables);
        variables.add(this.voltage);
    }

    @Override
    public void collectRule(LinearSystem.RelationProvider equations) {
        super.collectRule(equations);

        //Ground node don't provide kirchoff current rule, but its voltage is always zero
        if(isGrounded()){
            equations.stampCoefficient(this.voltage, 1);
            equations.stampConstant(0.0);
            equations.endRelation();
            return;
        }

        //implementation of default kirchoff current rule
        if (connection.isEmpty()) return;
        for (CircuitEdge edge : connection) {
            double coef = edge.shouldRevert(this) ? -1.0 : 1.0;
            equations.stampCoefficient(edge.getCurrent(), coef);
        }
        equations.stampConstant(0.0);
        equations.endRelation();
    }

    @Override
    public void tick(){

    }

    public boolean isGrounded() {
        return ground;
    }

    protected void setGround(boolean ground) {
        this.ground = ground;
    }

    /**
     * Only modify information in the scope of circuit node itself, do not modify field in the circuit and world.
     */
    public boolean connectEdge(CircuitEdge egde, boolean simulate){
        if(!simulate)
            connection.add(egde);
        return true;
    }

    /**
     * Only modify information in the scope of circuit node itself, do not modify field in the circuit and world.
     */
    public boolean disconnect(CircuitEdge edge, boolean simulate){
        if(simulate)
            return connection.contains(edge);
        return connection.remove(edge);
    }

    public Set<CircuitEdge> getConnection(){
        return Collections.unmodifiableSet(this.connection);
    }

    public boolean hasComponent() {
        return component != null;
    }

    public CircuitComponent getComponent() {
        return component;
    }

    public void setComponent(CircuitComponent component) {
        this.component = component;
    }

    public Set<CircuitNode> getAdjacent(){
        LinkedHashSet<CircuitNode> adjacent = new LinkedHashSet<>();
        getConnection().forEach(e -> {
            adjacent.add(e.getConnection(0));
            adjacent.add(e.getConnection(1));
        });
        adjacent.remove(this);
        return adjacent;
    }

    public DoubleVar getVoltage() {
        return voltage;
    }

    @Override
    public void save(CompoundTag tag) {
        super.save(tag);
        tag.putBoolean(CoreStrings.NODE_GROUND, isGrounded());
        tag.putDouble(CoreStrings.NODE_VOLTAGE, getVoltage().getValue());
        if (getComponent() != null) {
            TagUtil.putUUID(tag, CoreStrings.NODE_COMPONENT, getComponent().getId());
        }
    }

    /**
     * Restores ground and voltage from {@code tag}. Registry id is set by {@link CircuitElement#deserialize}
     * before {@link Circuit#addNode} and this call.
     */
    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        setGround(tag.getBoolean(CoreStrings.NODE_GROUND));
        getVoltage().setValue(tag.getDouble(CoreStrings.NODE_VOLTAGE));
    }
}
