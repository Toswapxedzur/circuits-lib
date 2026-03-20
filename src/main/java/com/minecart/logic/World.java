package com.minecart.logic;

import com.minecart.logic.component.CircuitEdge;
import com.minecart.logic.component.CircuitNode;

import java.util.ArrayList;
import java.util.List;

public class World {
    public List<Circuit> circuits;

    public World(){
        circuits = new ArrayList<>();
    }

    public void tick(){
        for(Circuit circuit : circuits){
            circuit.tick();
        }
    }

    public void create(CircuitNode node){
        Circuit circuit = new Circuit();
        circuit.setWorld(this);
        circuits.add(circuit);
        node.setWorld(this);
        node.setCircuit(circuit);
    }

    public CircuitEdge connect(CircuitNode node1, CircuitNode node2){
        if(node1.getWorld() != this || node2.getWorld() != this)
            return null;
        CircuitEdge edge = new CircuitEdge();
        edge.connect(node1, node2);
        Circuit circuit1 = node1.getCircuit(), circuit2 = node2.getCircuit();
        if(circuit1 != circuit2)
            circuit2.mergeInto(circuit1);
        circuit1.nodes.add(node1);
        circuit1.nodes.add(node2);
        circuit1.edges.add(edge);
        circuit1.updateTopology();
        return edge;
    }

    public boolean disconnect(CircuitEdge edge){
        CircuitNode node1 = edge.getConnection()[0];
        CircuitNode node2 = edge.getConnection()[1];
        if(node1.getCircuit() != node2.getCircuit())
            return false;
        Circuit circuit = node1.getCircuit();
        if(!node1.disconnect(edge, true) || node2.disconnect(edge, true))
            return false;
        node1.disconnect(edge, false);
        node2.disconnect(edge, false);
        circuit.seperate(node1, node2);
        return true;
    }

    public boolean destroy(CircuitNode node){
        if(!node.destroy(true))
            return false;
        node.destroy(false);
        return true;
    }
}
