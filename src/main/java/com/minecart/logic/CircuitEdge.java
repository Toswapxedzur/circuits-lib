package com.minecart.logic;

import com.google.common.graph.EndpointPair;
import com.minecart.math.function.DoubleVar;
import com.minecart.misc.CurrentFlow;

public class CircuitEdge extends CircuitElement {

    //positive: from first to second
    protected DoubleVar current;

    protected CircuitNode start;
    protected CircuitNode end;

    protected CircuitComponent component;

    public CircuitEdge(World world){
        setWorld(world);
        current = DoubleVar.create();
    }

    @Override
    public void tick(){

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
}
