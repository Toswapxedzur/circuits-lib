package com.minecart.logic;

import com.minecart.elements.component.BJTransistor;
import com.minecart.foundation.Circuit;
import com.minecart.registry.AllComponents;
import com.minecart.variant.Informations;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a {@link ServerWorld}'s electrical circuit from the physical placements' <b>geometry</b> — connectors
 * that coincide in world space (x,z) become one shared {@link CircuitNode} (a union-find "connector field",
 * replacing the old grid post-sharing), conductors union all their terminals, devices attach an element
 * between the merged nodes, and transistors fuse their ports on. This is domain logic (it produces the circuit
 * the ngspice solver runs), so it lives in {@code core}; the {@code display} board only computes each part's
 * terminal positions + electrical kind and hands over a plain, gdx-free {@link Part} list.
 */
public final class PhysicalCircuitBuilder {
    private PhysicalCircuitBuilder() {}

    /** A placed part's electrical role. Devices ({@code RESISTOR}…{@code BATTERY}) attach an element between
     *  terminals 0 and 1; {@code CONDUCTOR} unions ALL its terminals; {@code TRANSISTOR} is a 3-terminal BJT;
     *  {@code NONE} contributes nothing (e.g. an open switch, or a place-only part). */
    public enum Kind { NONE, CONDUCTOR, RESISTOR, DIODE, CAPACITOR, BATTERY, SOLAR, TRANSISTOR }

    /**
     * One placed part in the plan. {@code xz} is its terminal world positions as flat (x,z) pairs in
     * <b>terminal-index order</b> (terminal k at {@code xz[2k], xz[2k+1]}). Device params: RESISTOR
     * {@code [ohms]}, DIODE {@code [forwardR, reverseR]}, CAPACITOR {@code [farads, internalR]}, BATTERY
     * {@code [volts, internalR]}; CONDUCTOR/TRANSISTOR/NONE ignore it. {@code index} is echoed back in the
     * result so the caller can find each part's edge.
     */
    public record Part(int index, float[] xz, Kind kind, double[] params) {}

    /** The built circuit's edges to read back: {@code index → primary edge} (device edge, or a transistor's
     *  collector edge) for current/glow, plus the last battery placed (for a HUD readout). */
    public record Result(Map<Integer, com.minecart.logic.CircuitEdge> edges, com.minecart.logic.CircuitEdge lastBattery) {}

    /**
     * Clears {@code world}'s circuits and rebuilds them from {@code parts}. Call on every place/remove.
     */
    public static Result build(ServerWorld world, List<Part> parts) {
        for (Circuit c : new ArrayList<>(world.getCircuits())) {
            world.removeCircuit(c);
        }
        Map<Integer, CircuitEdge> edges = new HashMap<>();
        CircuitEdge lastBattery = null;
        ConnectorField field = new ConnectorField(world);

        // Pass 1: conductors union ALL their terminals into one net (a wire always; a closed switch is passed
        // as CONDUCTOR, an open one as NONE). N-terminal aware — a 3-way tee joins all three legs.
        for (Part p : parts) {
            if (p.kind() != Kind.CONDUCTOR) continue;
            int n = p.xz().length / 2;
            for (int j = 1; j < n; j++) {
                field.union(p.xz()[0], p.xz()[1], p.xz()[2 * j], p.xz()[2 * j + 1]);
            }
        }

        // Pass 2: two-terminal devices attach their element between the (now-merged) coincident-connector nodes.
        for (Part p : parts) {
            if (!isDevice(p.kind()) || p.xz().length < 4) continue;
            CircuitNode a = field.at(p.xz()[0], p.xz()[1]);
            CircuitNode b = field.at(p.xz()[2], p.xz()[3]);
            if (a == b) continue; // both terminals shorted onto one net
            double[] q = p.params();
            CircuitEdge e = switch (p.kind()) {
                case RESISTOR -> world.connect(AllComponents.RESISTOR, a, b, new Informations.ResistorInfo(q[0]));
                case DIODE -> world.connect(AllComponents.DIODE, a, b, new Informations.DiodeInfo(q[0], q[1]));
                case CAPACITOR -> world.connect(AllComponents.CAPACITOR, a, b, new Informations.CapacitorInfo(q[0], q[1]));
                case BATTERY -> world.connect(AllComponents.BATTERY, a, b, new Informations.BatteryInfo(q[0], q[1]));
                case SOLAR -> world.connect(AllComponents.SOLAR_CELL, a, b, solarInfo(q)); // q[0] = irradiance 0..1
                default -> null;
            };
            if (e != null) {
                edges.put(p.index(), e);
                if (p.kind() == Kind.BATTERY) lastBattery = e;
            }
        }

        // Pass 3: transistors — a true 3-terminal BJT, fused onto the coincident-connector nodes. Terminal-index
        // order: 0 = collector, 1 = emitter, 2 = base (the stem). ngspice models I_C = beta·I_B. After pass 2 so
        // any wire/device already at a transistor stud gets repointed onto the port by combineNodes.
        for (Part p : parts) {
            if (p.kind() != Kind.TRANSISTOR || p.xz().length < 6) continue;
            BJTransistor bjt = AllComponents.BJ_TRANSISTOR.create(world);
            ServerCircuit tc = new ServerCircuit();
            tc.setWorld(world);
            world.addCircuit(tc);
            tc.addComponent(bjt);
            bjt.generate();
            fusePort(world, field, p.xz()[4], p.xz()[5], bjt.getPort(0)); // base      ← terminal 2 (stem)
            fusePort(world, field, p.xz()[0], p.xz()[1], bjt.getPort(1)); // collector ← terminal 0
            fusePort(world, field, p.xz()[2], p.xz()[3], bjt.getPort(2)); // emitter   ← terminal 1
            if (bjt.getEdgeCollector() != null) edges.put(p.index(), bjt.getEdgeCollector());
        }

        return new Result(edges, lastBattery);
    }

    private static boolean isDevice(Kind k) {
        return k == Kind.RESISTOR || k == Kind.DIODE || k == Kind.CAPACITOR || k == Kind.BATTERY || k == Kind.SOLAR;
    }

    /** A default photovoltaic cell with its live irradiance (0..1) taken from the plan ({@code q[0]}), 1.0 if
     *  absent. The display computes irradiance from the incident light + shadow at the cell's face (S2). */
    private static Informations.SolarCellInfo solarInfo(double[] q) {
        Informations.SolarCellInfo si = new Informations.SolarCellInfo(0.1, 1e-9, 6.0, 1.0, 1000.0);
        si.setIrradiance(q != null && q.length > 0 ? q[0] : 1.0);
        return si;
    }

    /** Fuses a component's port node onto the board net at (x,z): {@link ServerWorld#combineNodes} elects the
     *  registered port as survivor, so every wire/device coincident at that stud repoints onto it and the two
     *  circuits merge. The field is rebound so a later lookup of that net returns the port. */
    private static void fusePort(ServerWorld world, ConnectorField field, float x, float z, CircuitNode port) {
        CircuitNode net = field.at(x, z);
        if (net == port) return;
        if (world.combineNodes(net, port)) {
            field.rebind(x, z, port);
        }
    }

    /** Quantised connector key (rounds to 2 units; y is dropped — a board post is one vertical conductor). */
    public static String key(float x, float z) {
        return Math.round(x / 2f) + "," + Math.round(z / 2f);
    }

    /** Union-find over connectors that COINCIDE in world (x,z) → one shared {@link CircuitNode} each. */
    private static final class ConnectorField {
        private final ServerWorld world;
        private final Map<String, Integer> keyId = new HashMap<>();
        private final List<Integer> parent = new ArrayList<>();
        private final Map<Integer, CircuitNode> node = new HashMap<>();

        ConnectorField(ServerWorld world) {
            this.world = world;
        }

        private int id(float x, float z) {
            return keyId.computeIfAbsent(key(x, z), k -> {
                parent.add(parent.size());
                return parent.size() - 1;
            });
        }

        private int find(int i) {
            while (parent.get(i) != i) {
                parent.set(i, parent.get(parent.get(i)));
                i = parent.get(i);
            }
            return i;
        }

        void union(float x1, float z1, float x2, float z2) {
            int ra = find(id(x1, z1)), rb = find(id(x2, z2));
            if (ra != rb) parent.set(ra, rb);
        }

        CircuitNode at(float x, float z) {
            return node.computeIfAbsent(find(id(x, z)),
                    r -> world.createNode(AllComponents.CONNECTION));
        }

        void rebind(float x, float z, CircuitNode n) {
            node.put(find(id(x, z)), n);
        }
    }
}
