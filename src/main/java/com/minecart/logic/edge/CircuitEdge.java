package com.minecart.logic.edge;

import com.google.common.graph.EndpointPair;
import com.minecart.logic.Component;
import com.minecart.logic.CurrentFlow;
import com.minecart.logic.component.CircuitNode;

public class CircuitEdge extends Component {
    //positive: from first to second
    protected float current;

    protected CircuitNode[] connection;

    public CircuitEdge(){
        connection = new CircuitNode[2];
    }

    public CircuitNode[] getConnection() {
        return connection;
    }

    public boolean connect(CircuitNode fromConnect, CircuitNode toConnect){
        if(connection[0] == null && connection[1] == null){
            connection[0] = fromConnect;
            connection[1] = toConnect;
            if(fromConnect.connectEdge(this, true) && toConnect.connectEdge(this, true)) {
                fromConnect.connectEdge(this, false);
                toConnect.connectEdge(this, false);
                return true;
            }
        }
        return false;
    }

    public CurrentFlow flowDirection(CircuitNode node){
        if(current == 0f)
            return CurrentFlow.NO;
        if(connection[sourceInx()] == node)
            return CurrentFlow.OUT;
        return CurrentFlow.IN;
    }

    protected int sourceInx(){
        return current < 0 ? 1 : 0;
    }

    protected int targetInx(){
        return current < 0 ? 0 : 1;
    }

    public CircuitNode getSource(){
        return connection[sourceInx()];
    }

    public CircuitNode getTarget(){
        return connection[targetInx()];
    }

    public EndpointPair<CircuitNode> incidentNodes(){
        return EndpointPair.ordered(getSource(), getTarget());
    }
}
