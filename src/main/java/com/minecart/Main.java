package com.minecart;

import com.minecart.math.function.DoubleVar;
import com.minecart.variant.type.BatteryInformation;
import com.minecart.variant.type.ResistorInformation;
import com.minecart.component.Battery;
import com.minecart.logic.CircuitEdge;
import com.minecart.component.Junction;
import com.minecart.component.Resistor;
import com.minecart.logic.World;
import com.minecart.math.function.Expression;
import com.minecart.registry.AllComponents;
import org.apache.commons.math3.util.Pair;

import java.util.List;

import static com.minecart.math.function.Expression.ExpressionBuilder.*;

public class Main {
    public static void main(String[] args) {

        System.out.println("=== BOOTING MNA TEST SUITE ===");

//        testSeriesCircuit();
//        testParallelCircuit();
        testShortCircuit();
    }

    public static void testSimplification(DoubleVar v1, DoubleVar v2) {
        System.out.println("--- Test 1: Simplification ---");

        Expression expr = add(
                mul(variable(v1), value(1.0)),  // Identity: Should become v1
                mul(variable(v2), value(0.0)),  // Zero rule: Should vanish
                mul(value(5.0), value(2.0))     // Constant fold: Should become 10.0
        );

        System.out.println("Raw Tree:  " + expr.toString());
        expr.simplify();
        System.out.println("Simplified: " + expr.toString());
        System.out.println();
    }

    /**
     * TEST 2: Term Expansion (The FOIL Method)
     * Simulates: (v1 + 2.0) * (v2 + 3.0)
     * Expected Output after simplify(): (+ (* (v$variable$) (v$variable$)) (* (c3.0) (v$variable$)) (* (c2.0) (v$variable$)) (c6.0))
     */
    public static void testExpansion(DoubleVar v1, DoubleVar v2) {
        System.out.println("--- Test 2: Term Expansion ---");

        Expression s1 = add(variable(v1), value(2.0));
        Expression s2 = add(variable(v2), value(3.0));
        Expression expr = mul(s1, s2);

        System.out.println("Raw Tree:  " + expr.toString());
        expr.simplify();
        System.out.println("Expanded:   " + expr.toString());
        System.out.println("Is Linear?  " + expr.isLinear() + " (Expected: false)\n");
    }

    /**
     * TEST 3: Linear Matrix Extraction
     * Simulates: 4(v1) + (-2)(v2) - 10.0
     * Expected Output: Map with {v1=4.0, v2=-2.0} and Intercept = -10.0
     */
    public static void testLinearExtraction(DoubleVar v1, DoubleVar v2) {
        System.out.println("--- Test 3: Linear Extraction ---");

        Expression expr = add(
                coef(4.0, v1),
                coef(-2.0, v2),
                value(-10.0)
        );

        System.out.println("Expression: " + expr.toString());
        System.out.println("Is Linear?  " + expr.isLinear() + " (Expected: true)");

        Pair<List<Pair<Double, DoubleVar>>, Double> linearData = expr.toLinear();

        System.out.println("Intercept:  " + linearData.getSecond() + " (Expected: -10.0)");
        System.out.println("Variables Extracted:");
        for (Pair<Double, DoubleVar> term : linearData.getFirst()) {
            System.out.println(" -> Coefficient: " + term.getFirst());
        }
    }

    /**
     * TEST 1: The Simple Series Loop (Ohm's Law)
     * Setup: 10V Battery (2 Ohm internal) + 8 Ohm Resistor
     * Math: Total Resistance = 10 Ohms. 10V / 10Ohm = 1.0 Amps.
     */
    public static void testSeriesCircuit() {
        System.out.println("\n--- Running Test 1: Series Circuit ---");
        World world = new World();

        Battery battery = world.createNode(AllComponents.BATTERY, new BatteryInformation(10.0, 2.0));
        Resistor resistor = world.createNode(AllComponents.RESISTOR, new ResistorInformation(8.0));

        // Connect them and unwrap the Optional. If it fails to connect, the test crashes here.
        CircuitEdge outWire = world.connect(battery, resistor).orElseThrow();
        world.connect(resistor, battery).orElseThrow();

        world.tick();

        double current = Math.abs(outWire.getCurrent().getValue());
        System.out.println("Loop Current: " + current + " A (Expected: 1.0)");
    }

    /**
     * TEST 2: The Parallel Split (Kirchhoff's Current Law)
     * Setup: 12V Battery (1 Ohm internal) splitting into two 10 Ohm Resistors.
     * Math: Two 10 Ohm resistors in parallel = 5 Ohms.
     * Total Resistance = 5 + 1 (internal) = 6 Ohms.
     * Total Current = 12V / 6Ohm = 2.0 Amps.
     * Branch Current = 1.0 Amps each.
     */
    public static void testParallelCircuit() {
        System.out.println("\n--- Running Test 2: Parallel Circuit ---");
        World world = new World();

        Battery battery = world.createNode(AllComponents.BATTERY, new BatteryInformation(12.0, 1.0));
        Resistor r1 = world.createNode(AllComponents.RESISTOR, new ResistorInformation(10.0));
        Resistor r2 = world.createNode(AllComponents.RESISTOR, new ResistorInformation(10.0));
        Junction topJunction = world.createNode(AllComponents.JUNCTION);
        Junction bottomJunction = world.createNode(AllComponents.JUNCTION);

        // Wire Battery to Junctions
        CircuitEdge batPos = world.connect(battery, topJunction).orElseThrow();
        world.connect(bottomJunction, battery).orElseThrow();

        // Wire Junctions to Resistors
        CircuitEdge topToR1 = world.connect(topJunction, r1).orElseThrow();
        world.connect(r1, bottomJunction).orElseThrow();

        CircuitEdge topToR2 = world.connect(topJunction, r2).orElseThrow();
        world.connect(r2, bottomJunction).orElseThrow();

        world.tick();

        System.out.println("Total Output Current: " + Math.abs(batPos.getCurrent().getValue()) + " A (Expected: 2.0)");
        System.out.println("R1 Branch Current: " + Math.abs(topToR1.getCurrent().getValue()) + " A (Expected: 1.0)");
        System.out.println("R2 Branch Current: " + Math.abs(topToR2.getCurrent().getValue()) + " A (Expected: 1.0)");
    }

    /**
     * TEST 3: The Short Circuit (Singular Matrix Prevention)
     * Setup: 10V Battery (0.1 Ohm internal) connected directly back to itself.
     * Math: This proves your internal resistance trick prevents Apache Commons Math
     * from throwing a "Singular Matrix" crash.
     * 10V / 0.1Ohm = 100 Amps.
     */
    public static void testShortCircuit() {
        System.out.println("\n--- Running Test 3: Short Circuit ---");
        World world = new World();

        Battery battery = world.createNode(AllComponents.BATTERY, new BatteryInformation(10.0, 0.1));

        // Connect the battery directly to itself
        CircuitEdge shortWire = world.connect(battery, battery).orElseThrow();

        try {
            world.tick();
            double current = Math.abs(shortWire.getCurrent().getValue());
            System.out.println("Short Circuit Survived! Massive Current: " + current + " A (Expected: 100.0)");
        } catch (Exception e) {
            System.err.println("CRASH! The matrix solver failed to handle the short circuit.");
            e.printStackTrace();
        }
    }
}