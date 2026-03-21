package com.minecart.logic;

import com.minecart.component.CircuitEdge;
import com.minecart.component.CircuitNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class World {
    public Double ELECTRICAL_EPSILON = 1e-9;
    public Double MAXIMUN_WIRE_CURRENT = 1e6;

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
        node.setWorld(this);
        Circuit circuit = createCircuit();
        node.setCircuit(circuit);
        circuit.nodes().add(node);
    }

    protected Circuit createCircuit(){
        Circuit circuit = new Circuit();
        circuit.setWorld(this);
        circuits.add(circuit);
        circuit.updateTopology();
        return circuit;
    }

    public Optional<CircuitEdge> connect(CircuitNode node1, CircuitNode node2){
        if(node1.getWorld() != this || node2.getWorld() != this)
            throw new IllegalArgumentException("Can't coonect node that doesn't belong to the current World");
        CircuitEdge edge = new CircuitEdge();
        edge.connect(node1, node2);
        if(!node1.connectEdge(edge, true) || !node2.connectEdge(edge, true))
            return Optional.empty();
        node1.connectEdge(edge, false);
        node2.connectEdge(edge, false);
        Circuit circuit1 = node1.getCircuit();
        Circuit circuit2 = node2.getCircuit();
        if(circuit1 != circuit2) {
            circuit2.mergeInto(circuit1);
            circuits.remove(circuit2);
        }
        circuit1.addEdge(edge);
        circuit1.updateTopology();
        return Optional.of(edge);
    }

    public boolean disconnect(CircuitEdge edge){
        CircuitNode node1 = edge.getConnection(0);
        CircuitNode node2 = edge.getConnection(1);
        if(node1.getCircuit() != node2.getCircuit())
            return false;
        Circuit circuit = node1.getCircuit();
        if(!node1.disconnect(edge, true) || node2.disconnect(edge, true))
            return false;
        Circuit newCircuit = new Circuit();
        newCircuit.setWorld(this);
        boolean createCircuit = circuit.seperate(node1, node2, edge, newCircuit);
        if(createCircuit)
            this.circuits.add(newCircuit);
        return true;
    }

    public boolean destroy(CircuitNode node){
        if(!node.getCircuit().destroy(node, true))
            return false;
        node.getCircuit().destroy(node, false);
        return true;
    }
}
