package com.minecart.component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class TwoConnector extends CircuitNode{
    protected List<CircuitEdge> edges;

    public TwoConnector(){
        edges = new ArrayList<>();
    }

    @Override
    public boolean connectEdge(CircuitEdge egde, boolean simulate) {
        if(edges.size() >= 2)
            return false;
        if(!simulate)
            edges.add(egde);
        return true;
    }

    @Override
    public Set<CircuitEdge> getConnection() {
        Set<CircuitEdge> circuitEdges = super.getConnection();
        for(CircuitEdge edge : edges){
            circuitEdges.add(edge);
        }
        return circuitEdges;
    }

    @Override
    public boolean disconnect(CircuitEdge other, boolean simulate) {
        if(simulate)
            return edges.contains(other);
        return edges.remove(other);
    }
}
