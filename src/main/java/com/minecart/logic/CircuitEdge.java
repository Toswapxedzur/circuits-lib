package com.minecart.logic;

import com.google.common.graph.EndpointPair;
import com.minecart.math.DoubleVar;
import com.minecart.misc.CurrentFlow;
import com.minecart.serialization.TagSerializable;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;

import java.io.IOException;
import java.util.Set;

public class CircuitEdge extends CircuitElement implements TagSerializable {
    public static final double MAX_CURRENT = 1e15;

    //positive: from first to second
    protected DoubleVar current;

    protected CircuitNode start;
    protected CircuitNode end;

    protected CircuitComponent component;

    protected boolean overpowered;

    public CircuitEdge(World world){
        setWorld(world);
        current = DoubleVar.create();
        overpowered = false;
    }

    @Override
    public void collectVariable(Set<DoubleVar> variables) {
        super.collectVariable(variables);
        variables.add(current);
    }

    @Override
    public void tick(){
        overpowered = shortCircuit();
    }

    /**
     * Whether this edge was last evaluated as overpowered ({@link #shortCircuit()}) after the previous tick.
     * Used with {@link #shortCircuit()} before {@link #tick()} to detect a transition into overpowered.
     */
    public boolean isOverpowered() {
        return overpowered;
    }

    public boolean shortCircuit(){
        return Math.abs(current.getValue()) > MAX_CURRENT;
    }

    public boolean connect(CircuitNode fromConnect, CircuitNode toConnect, boolean simulate){
        if(hasComponent())
            return false;
        if(!simulate) {
            start = fromConnect;
            end = toConnect;
        }
        return true;
    }

    public boolean disconnect(boolean simulate){
        if(hasComponent())
            return false;
        if(!simulate) {
            start = null;
            end = null;
        }
        return true;
    }

    public CircuitNode getStart() {
        return start;
    }

    public CircuitNode getEnd() {
        return end;
    }

    public CircuitComponent getComponent() {
        return component;
    }

    public boolean hasComponent() {
        return component != null;
    }

    public void setComponent(CircuitComponent component) {
        this.component = component;
    }

    public boolean isConnected(){
        return start != null && end != null;
    }

    public CircuitNode getConnection(int index) {
        return index == 0 ? start : end;
    }

    public int getIndex(CircuitNode node){
        return getConnection(0) == node ? 0 : 1;
    }

    public CircuitNode getOther(CircuitNode node) {
        return getIndex(node) == 0 ? getConnection(1) : getConnection(0);
    }

    public boolean connectTo(CircuitNode node){
        return getConnection(0) == node || getConnection(1) == node;
    }

    public DoubleVar getCurrent() {
        return current;
    }

    public boolean shouldRevert(CircuitNode node){
        return node.equals(getConnection(1));
    }

    public CurrentFlow flowDirection(CircuitNode node){
        if(current.getValue() == 0f)
            return CurrentFlow.NO;
        if(getConnection(sourceInx()) == node)
            return CurrentFlow.OUT;
        return CurrentFlow.IN;
    }

    protected int sourceInx(){
        return current.getValue() < 0 ? 1 : 0;
    }

    protected int targetInx(){
        return current.getValue() < 0 ? 0 : 1;
    }

    public CircuitNode getSource(){
        return getConnection(sourceInx());
    }

    public CircuitNode getTarget(){
        return getConnection(targetInx());
    }

    public EndpointPair<CircuitNode> incidentNodes(){
        return EndpointPair.ordered(getSource(), getTarget());
    }

    @Override
    public void save(CompoundTag tag) throws IOException {
        saveElementHeader(tag);
        if (getStart() != null) {
            TagUtil.putUUID(tag, "start", getStart().getId());
        }
        if (getEnd() != null) {
            TagUtil.putUUID(tag, "end", getEnd().getId());
        }
        tag.putDouble("current", getCurrent().getValue());
        tag.putBoolean("overpowered", overpowered);
    }

    /**
     * Restores electrical state. Endpoints are resolved and wired by {@link Circuit} after all nodes are loaded.
     */
    @Override
    public void load(CompoundTag tag) throws IOException {
        getCurrent().setValue(tag.getDouble("current"));
        overpowered = tag.getBoolean("overpowered");
    }
}
