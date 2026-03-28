package com.minecart;

import com.minecart.action.ActionTypes;
import com.minecart.action.Actions;
import com.minecart.component.Battery;
import com.minecart.component.Resistor;
import com.minecart.event.events.ServerTickEvent;
import com.minecart.event.events.ShortCircuitEvent;
import com.minecart.logic.CircuitEdge;
import com.minecart.logic.CircuitNode;
import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;
import com.minecart.registry.AllComponents;

//prevent concurrent modification (deletion, addition)
public class Main {
    public static final ServerLevel ENGINE = new ServerLevel();

    public static void main(String[] args) {
        // 1. Boot up the engine
        ServerLevel engine = new ServerLevel();

        // 2. Register our listener to catch the explosion
        engine.register(ShortCircuitEvent.class, event -> {
            System.err.println("\n[EVENT BUS] ⚠️ SHORT CIRCUIT DETECTED! ⚠️");
            System.err.println("Dimension World ID: " + event.getWorld().hashCode());
            System.err.println("Wires melted:");
            for (CircuitEdge edge : event.getEdges()) {
                System.err.println(" - " + edge.toString() + " (Resistance too low!)");
            }
        });

        // 3. Create the physical world scenario
        System.out.println("Building the circuit...");
        ServerWorld overworld = engine.createWorld();

        // (Assuming you have these mock components defined in your registry)
        CircuitNode batteryPositive = overworld.createNode(AllComponents.CONNECTION); // 12 Volts
        CircuitNode batteryNegative = overworld.createNode(AllComponents.CONNECTION);  // 0 Volts

        // We connect them directly with standard copper wires (No Resistor!)
        Resistor wire1 = overworld.connect(AllComponents.RESISTOR, batteryPositive, batteryNegative);
        AllComponents.RESISTOR.perform(wire1, ActionTypes.SET_RESISTANCE, new Actions.SetResistanceAction(1e-18));

        Battery bat1 = overworld.connect(AllComponents.BATTERY, batteryNegative, batteryPositive);
        AllComponents.BATTERY.perform(bat1, ActionTypes.SET_VOLTAGE, new Actions.SetVoltageAction(2));
        AllComponents.BATTERY.perform(bat1, ActionTypes.SET_RESISTANCE, new Actions.SetResistanceAction(1e-18));

        // 4. Tick the engine
        System.out.println("Starting Simulation Tick...");

        engine.tick();

        System.out.println(bat1.getCurrent().getValue());
    }
}