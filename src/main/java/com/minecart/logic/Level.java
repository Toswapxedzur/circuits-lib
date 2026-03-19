package com.minecart.logic;

public class Level {
    public Circuit circuit;

    public Level(){
        circuit = new Circuit();
    }

    public void tick(){
        circuit.tick();
    }
}
