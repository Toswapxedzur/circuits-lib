package com.minecart.logic;

import com.google.common.collect.ImmutableList;
import com.google.common.graph.EndpointPair;
import com.minecart.component.CircuitNode;
import com.minecart.component.Component;
import com.minecart.math.function.DoubleVariable;
import com.minecart.misc.CurrentFlow;

public class CircuitEdge extends Component {
    //positive: from first to second
    protected DoubleVariable current;

    protected DoubleVariable voltage;

    protected CircuitNode[] connection;

    public CircuitEdge(World world){
        connection = new CircuitNode[2];
        setWorld(world);
        current = getWorld().createDoubleVar();
        voltage = getWorld().createDoubleVar();
    }

    @Override
    public void tick(){

    }

    public CircuitNode getConnection(int index) {
        return connection[index];
    }

    public int getIndex(CircuitNode node){
        return getConnection(0) == node ? 0 : 1;
    }

    public CircuitNode getOther(CircuitNode node) {
        return getIndex(node) == 0 ? getConnection(1) : getConnection(0);
    }

    public ImmutableList<CircuitNode> getConnections(){
        return ImmutableList.copyOf(connection);
    }

    public boolean connectTo(CircuitNode node){
        return getConnection(0) == node || getConnection(1) == node;
    }

    public DoubleVariable getVoltage() {
        return voltage;
    }

    public DoubleVariable getCurrent() {
        return current;
    }

    public boolean connect(CircuitNode fromConnect, CircuitNode toConnect){
        if(connection[0] == null && connection[1] == null){
            connection[0] = fromConnect;
            connection[1] = toConnect;
            return true;
        }
        return false;
    }

    public boolean shouldRevert(CircuitNode node){
        return node.equals(getConnection(1));
    }

    public CurrentFlow flowDirection(CircuitNode node){
        if(current.getValue() == 0f)
            return CurrentFlow.NO;
        if(connection[sourceInx()] == node)
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
        return connection[sourceInx()];
    }

    public CircuitNode getTarget(){
        return connection[targetInx()];
    }

    public boolean selfLoop(){
        return connection[0].equals(connection[1]);
    }

    public EndpointPair<CircuitNode> incidentNodes(){
        return EndpointPair.ordered(getSource(), getTarget());
    }
}
