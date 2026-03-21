package com.minecart;

import com.minecart.logic.World;
import com.minecart.component.Battery;
import com.minecart.component.CircuitEdge;
import com.minecart.component.Junction;
import com.minecart.component.Resistor;
import com.minecart.math.function.Expression;
import com.minecart.math.function.ExpressionParser;

public class Main {
    public static void main(String[] args) {
        System.out.println("--- Booting Parallel Circuit Simulator ---");
        World world = new World();

        // 1. Instantiate YOUR components (Using your default values)
        Battery battery = new Battery(); // 20.0 Volts
        Resistor r1 = new Resistor();    // 10.0 Ohms
        Resistor r2 = new Resistor();    // 10.0 Ohms
        Junction topJunction = new Junction();
        Junction bottomJunction = new Junction();

        // 2. Register them to the World
        world.create(battery);
        world.create(r1);
        world.create(r2);
        world.create(topJunction);
        world.create(bottomJunction);

        System.out.println("Wiring components...");

        // 3. Connect Battery
        // Based on your Battery.java: edges.get(0) is positive, edges.get(1) is negative
        CircuitEdge batPos = world.connect(battery, topJunction).get();
        CircuitEdge batNeg = world.connect(battery, bottomJunction).get();

        // 4. Connect Resistor 1 (Parallel Branch A)
        // Based on your Resistor.java: edges.get(0) is V1, edges.get(1) is V2
        CircuitEdge topToR1 = world.connect(topJunction, r1).get();
        CircuitEdge r1ToBot = world.connect(r1, bottomJunction).get();

        // 5. Connect Resistor 2 (Parallel Branch B)
        CircuitEdge topToR2 = world.connect(topJunction, r2).get();
        CircuitEdge r2ToBot = world.connect(r2, bottomJunction).get();

        // 6. Run the Matrix Solver
        System.out.println("Ticking World (Solving Matrix)...");
        for(Expression exp : world.circuits.get(0).getElectricalRules())
            System.out.println(ExpressionParser.encrypt(exp));
        world.tick();

        // 7. Output Results
        System.out.println("\n--- Simulation Results ---");

        // Voltage drop should be exactly 20V
        double vDiff = batPos.getVoltage().getValue() - batNeg.getVoltage().getValue();
        System.out.println("Voltage Drop across system: " + vDiff + " V (Expected: 20.0)");

        // Current should split perfectly
        System.out.println("\nTotal Current from Battery: " + Math.abs(batPos.getCurrent().getValue()) + " A (Expected: 4.0)");
        System.out.println("Current through R1: " + Math.abs(topToR1.getCurrent().getValue()) + " A (Expected: 2.0)");
        System.out.println("Current through R2: " + Math.abs(topToR2.getCurrent().getValue()) + " A (Expected: 2.0)");
    }
}