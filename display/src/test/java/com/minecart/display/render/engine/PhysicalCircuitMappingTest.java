package com.minecart.display.render.engine;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.math.Matrix4;
import com.minecart.elements.component.BJTransistor;
import com.minecart.logic.CircuitEdge;
import com.minecart.logic.CircuitNode;
import com.minecart.logic.ServerCircuit;
import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;
import com.minecart.registry.AllComponents;
import com.minecart.variant.Informations;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies the 3D-world → circuit mapping in {@link PhysicalBoardView#buildCircuit} for the two features added
 * 2026-09-20: (A/B) 3-terminal TRANSISTOR wiring — a placed NPN becomes a real core {@link BJTransistor} whose
 * ports fuse onto the board's coincident-connector nodes, and it solves {@code I_C = beta*I_B} through ngspice; and
 * (C) N-TERMINAL junctions — a tee unifies all THREE of its legs into one node, not just two.
 *
 * <p>Runs headless (a {@link HeadlessApplication} supplies {@code Gdx.files} so {@link PhysicalBoardView}'s model
 * loader can read the committed part JSONs); no GL context is created. Placements go in via
 * {@link PhysicalBoardView#addPlacementNoRender} (the render bake needs GL; the circuit build does not).
 *
 * <p>Transistor connector geometry (identity transform): collector = terminal 0 at (-12,0,0), emitter = terminal 1
 * at (+12,0,0), base = terminal 2 (the stem) at (0,0,-12). A resistor/tee spans ±12 along its local X.
 */
class PhysicalCircuitMappingTest {

    private static HeadlessApplication app;

    @BeforeAll
    static void boot() {
        if (Gdx.app == null) {
            app = new HeadlessApplication(new ApplicationAdapter() {}, new HeadlessApplicationConfiguration());
        }
    }

    @AfterAll
    static void shutdown() {
        if (app != null) app.exit();
    }

    private static Matrix4 at(float x, float z, float yawDeg) {
        return new Matrix4().setToTranslation(x, 0f, z).rotate(0f, 1f, 0f, yawDeg);
    }

    private static BJTransistor findTransistor(ServerWorld world) {
        for (com.minecart.foundation.Circuit c : world.getCircuits()) {
            for (com.minecart.logic.CircuitComponent comp : c.components()) {
                if (comp instanceof BJTransistor bjt) return bjt;
            }
        }
        return null;
    }

    /** A: a placed transistor becomes a real BJT that enforces I_C = beta*I_B when biased through its ports. */
    @Test
    void transistorSolvesCommonEmitterGain() {
        assumeTrue(ServerCircuit.SPICE_BACKEND, "ngspice backend not active");
        ServerLevel level = new ServerLevel();
        ServerWorld world = level.createWorld();

        PhysicalBoardView board = new PhysicalBoardView();
        board.addPlacementNoRender("transistor_npn", at(0f, 0f, 0f));
        board.buildCircuit(world);

        BJTransistor bjt = findTransistor(world);
        assertNotNull(bjt, "buildCircuit should create a core BJTransistor for a placed NPN");
        assertEquals(100.0, bjt.getInfo().getBeta(), 1e-9);

        // Bias exactly as BJTransistorTest: base battery 11V/9Ohm (emitter->base), collector supply 5V (emitter->
        // collector). Wire to the FUSED port nodes to prove they are live, usable circuit nodes.
        CircuitNode base = bjt.getPort(0), collector = bjt.getPort(1), emitter = bjt.getPort(2);
        world.connect(AllComponents.BATTERY, emitter, base, new Informations.BatteryInfo(11.0, 9.0));
        world.connect(AllComponents.BATTERY, emitter, collector, new Informations.BatteryInfo(5.0, 1e-3));

        level.tick();

        double iB = bjt.getEdgeBase().getCurrent().getValue();
        double iC = bjt.getEdgeCollector().getCurrent().getValue();
        assertTrue(Math.abs(iB) > 1e-3, "base current should be nonzero, was " + iB);
        assertEquals(100.0 * iB, iC, 1e-6, "collector current must be beta*base");
    }

    /** B: a device placed so a terminal coincides with the collector stud fuses onto the transistor's collector
     *  node (the combineNodes path) — the resistor edge ends up attached to the BJT collector port. */
    @Test
    void boardDeviceFusesOntoTransistorStud() {
        ServerLevel level = new ServerLevel();
        ServerWorld world = level.createWorld();

        PhysicalBoardView board = new PhysicalBoardView();
        board.addPlacementNoRender("transistor_npn", at(0f, 0f, 0f));      // collector stud at (-12,0,0)
        board.addPlacementNoRender("resistor", at(-24f, 0f, 0f));          // spans (-36,0,0)..(-12,0,0)=collector
        board.buildCircuit(world);

        BJTransistor bjt = findTransistor(world);
        assertNotNull(bjt);
        CircuitNode collector = bjt.getPort(1);
        CircuitEdge resistor = board.debugDeviceEdge(1);
        assertNotNull(resistor, "resistor placement should have produced a circuit edge");
        assertTrue(resistor.getStart() == collector || resistor.getEnd() == collector,
                "the resistor on the collector stud must share the transistor's collector node (fusion)");
    }

    /** C: a tee unifies ALL THREE legs. Two resistors — one on leg 0 (-12,0,0), one on the stem leg 2 (0,0,-12) —
     *  must end up sharing a node. Under the old 2-terminal union (legs 0+1 only) the stem stayed isolated. */
    @Test
    void teeUnifiesAllThreeLegs() {
        ServerLevel level = new ServerLevel();
        ServerWorld world = level.createWorld();

        PhysicalBoardView board = new PhysicalBoardView();
        board.addPlacementNoRender("tee_blue", at(0f, 0f, 0f));   // legs: (-12,0,0), (+12,0,0), stem (0,0,-12)
        board.addPlacementNoRender("resistor", at(-24f, 0f, 0f)); // touches leg 0 at (-12,0,0)
        board.addPlacementNoRender("resistor", at(0f, -24f, 90f)); // touches stem leg 2 at (0,0,-12)
        board.buildCircuit(world);

        CircuitEdge r1 = board.debugDeviceEdge(1); // on leg 0
        CircuitEdge r2 = board.debugDeviceEdge(2); // on the stem (leg 2)
        assertNotNull(r1);
        assertNotNull(r2);
        boolean shared = r1.getStart() == r2.getStart() || r1.getStart() == r2.getEnd()
                || r1.getEnd() == r2.getStart() || r1.getEnd() == r2.getEnd();
        assertTrue(shared, "tee must unify its stem (leg 2) with leg 0 — they should share a circuit node");
    }
}
