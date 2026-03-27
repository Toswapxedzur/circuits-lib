package com.minecart;

import com.minecart.action.ActionTypes;
import com.minecart.action.Actions;
import com.minecart.logic.CircuitNode;
import com.minecart.logic.ServerWorld;
import com.minecart.registry.AllComponents;
import com.minecart.component.Battery;
import com.minecart.component.Resistor;
import com.minecart.component.Capacitor;

public class Main {
    public static void main(String[] args) {
        System.out.println("--- Booting Action System Physics Tests ---\n");
        testDynamicCapacitanceChange();
    }

    private static void testDynamicCapacitanceChange() {
        System.out.println("Test 8: Dynamic Capacitance Change (Charge Conservation)");
        ServerWorld world = new ServerWorld();

        // 1. Create the nodes
        CircuitNode nodePos = world.createNode(AllComponents.CONNECTION);
        CircuitNode nodeMid = world.createNode(AllComponents.CONNECTION);
        CircuitNode nodeNeg = world.createNode(AllComponents.CONNECTION);

        // 2. Setup components strictly using the Action System
        Battery battery = world.connect(AllComponents.BATTERY, nodePos, nodeNeg);
        // Assuming you have a SetVoltageAction for the battery
        AllComponents.BATTERY.perform(battery, ActionTypes.SET_VOLTAGE, new Actions.SetVoltageAction(10.0));

        Resistor resistor = world.connect(AllComponents.RESISTOR, nodePos, nodeMid);
        AllComponents.RESISTOR.perform(resistor, ActionTypes.SET_RESISTANCE, new Actions.SetResistanceAction(9.9)); // 10 Ohms total

        Capacitor capacitor = world.connect(AllComponents.CAPACITOR, nodeMid, nodeNeg);
        AllComponents.CAPACITOR.perform(capacitor, ActionTypes.SET_CAPACITANCE, new Actions.SetCapacitanceAction(0.1));

        System.out.println("PHASE 1: Charging a 0.1F Capacitor with 10V for 20 ticks...");
        System.out.println("Tick | Cap Voltage | Cap Charge  | ServerCircuit Current");
        System.out.println("--------------------------------------------------");

        // Phase 1: Charge to ~6.32V
        for (int tick = 1; tick <= 20; tick++) {
            world.tick();
            printCapacitorState(tick, capacitor);
        }

        System.out.println("\n>>> ACTION DISPATCHED: CAPACITANCE HALVED TO 0.05F <<<\n");

        // The Disruption: The player uses a wrench or GUI to change the component.
        // This fires an Action. The Capacitor's internal handler updates 'C', but leaves 'Q' untouched.
        AllComponents.CAPACITOR.perform(capacitor, ActionTypes.SET_CAPACITANCE, new Actions.SetCapacitanceAction(0.05));

        System.out.println("PHASE 2: Voltage spikes, causing capacitor to discharge BACKWARDS into the battery...");
        System.out.println("Tick | Cap Voltage | Cap Charge  | ServerCircuit Current");
        System.out.println("--------------------------------------------------");

        // Phase 2: Watch the physics engine react
        for (int tick = 21; tick <= 40; tick++) {
            world.tick();
            printCapacitorState(tick, capacitor);
        }
    }

    private static void printCapacitorState(int tick, Capacitor cap) {
        double capacitance = cap.get().getCapacitance();
        double charge = cap.get().getCharge();
        double capVoltage = charge / capacitance;
        double current = cap.getCurrent().getValue();

        System.out.printf(" %2d  | %9.4f V | %9.4f C | %13.4f A\n", tick, capVoltage, charge, current);
    }
}