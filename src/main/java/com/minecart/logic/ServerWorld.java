package com.minecart.logic;

import com.minecart.registry.CircuitElementType;

import java.util.*;

public class ServerWorld {
    protected double tickRate = 0.05;
    public Set<ServerCircuit> circuits;

    public ServerWorld(){
        circuits = new LinkedHashSet<>();
    }

    public void tick(){
        for(ServerCircuit circuit : circuits){
            circuit.tick();
        }
    }

    public <T extends CircuitNode> T createNode(CircuitElementType<T> type){
        T node = type.create(this);
        node.setWorld(this);
        ServerCircuit circuit = createCircuit();
        node.setCircuit(circuit);
        circuit.nodes().add(node);
        return node;
    }

    protected ServerCircuit createCircuit(){
        ServerCircuit circuit = new ServerCircuit();
        circuit.setWorld(this);
        circuits.add(circuit);
        circuit.markDirty();
        return circuit;
    }

    public <T extends CircuitEdge> T connect(CircuitElementType<T> type, CircuitNode node1, CircuitNode node2){
        if(node1.getWorld() != this || node2.getWorld() != this)
            throw new IllegalArgumentException("Can't coonect node that doesn't belong to the current ServerWorld");
        T edge = type.create(this);
        edge.setWorld(this);
        if(!edge.connect(node1, node2, true))
            return null;
        edge.connect(node1, node2, false);
        if(!node1.connectEdge(edge, true) || !node2.connectEdge(edge, true))
            return null;
        node1.connectEdge(edge, false);
        node2.connectEdge(edge, false);
        ServerCircuit circuit1 = node1.getCircuit();
        ServerCircuit circuit2 = node2.getCircuit();
        if(circuit1 != circuit2) {
            circuit2.mergeInto(circuit1);
            circuits.remove(circuit2);
        }
        circuit1.addEdge(edge);
        circuit1.markDirty();
        return edge;
    }


    public boolean disconnect(CircuitEdge edge){
        CircuitNode node1 = edge.getConnection(0);
        CircuitNode node2 = edge.getConnection(1);
        if(node1.getCircuit() != node2.getCircuit())
            return false;
        ServerCircuit circuit = node1.getCircuit();
        if(!node1.disconnect(edge, true) || node2.disconnect(edge, true) || !edge.disconnect(true))
            return false;
        ServerCircuit newCircuit = new ServerCircuit();
        newCircuit.setWorld(this);
        boolean createCircuit = circuit.seperate(node1, node2, edge, newCircuit);
        if(createCircuit)
            this.circuits.add(newCircuit);
        return true;
    }

    public boolean destroy(CircuitNode node){
        if(node.getWorld() != this)
            throw new IllegalArgumentException("Can't connect node that doesn't belong to the current ServerWorld");

        ServerCircuit circuit = node.getCircuit();

        if(!circuit.destroy(node, true))
            return false;

        circuit.destroy(node, false);

        if (circuit.nodes().isEmpty()) {
            this.circuits.remove(circuit);
        }

        return true;
    }

    public double getTickRate() {
        return tickRate;
    }

    public void setTickRate(double tickRate) {
        this.tickRate = tickRate;
    }
}
