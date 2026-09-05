package com.minecart.spice;

import com.minecart.elements.component.BJTransistor;
import com.minecart.elements.edge.Battery;
import com.minecart.elements.edge.Capacitor;
import com.minecart.elements.edge.Diode;
import com.minecart.elements.edge.Resistor;
import com.minecart.elements.edge.Wire;
import com.minecart.logic.CircuitComponent;
import com.minecart.logic.CircuitEdge;
import com.minecart.logic.CircuitNode;
import com.minecart.variant.Informations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Electrical solve of one circuit tick through ngspice.
 *
 * <p>The circuit graph is emitted as a SPICE netlist: every {@link CircuitNode} becomes a node
 * ({@code 0} for the per-component ground the circuit already picks), every {@link CircuitEdge}
 * becomes its device in series with a 0 V "ammeter" source so its branch current is a first-class
 * vector ({@code i(vmN)}, positive from the edge's start to its end — the same convention the
 * built-in solver uses). Then ONE transient of exactly one tick is run with ngspice's adaptive,
 * error-controlled integration ({@code .tran ... uic}); capacitor charge is carried across ticks as
 * an initial condition, so the integration error is bounded per tick and never accumulates with
 * simulated time.
 *
 * <p>Element mapping:
 * <ul>
 *   <li>Wire: the ammeter alone (V_start = V_end).</li>
 *   <li>Resistor: {@code R}.</li>
 *   <li>Battery: EMF source from start to a mid node, internal resistance to the end
 *       (V_start - V_end = EMF + I·R, matching {@link Battery#collectRule}).</li>
 *   <li>Capacitor: {@code C} with {@code ic = Q/C} in series with its internal resistance; after the
 *       tick the charge is read back as C·V (no Euler step).</li>
 *   <li>Diode: piecewise resistance (forward / reverse) as a behavioural resistor with a steep
 *       tanh transition, so it stays the same idealised device the rest of the code expects.</li>
 *   <li>BJTransistor: its collector edge becomes a current-controlled current source
 *       {@code beta · i(base ammeter)}, exactly the constitutive rule the component stamps.</li>
 * </ul>
 * Any other edge type makes the circuit unsupported ({@link #solve} returns {@link Result#UNSUPPORTED}),
 * and the caller uses the built-in linear solver instead.
 */
public final class SpiceSolver {
    private static final Logger log = LoggerFactory.getLogger(SpiceSolver.class);

    public enum Result { OK, FAILED, UNSUPPORTED }

    private SpiceSolver() {}

    /** Netlist + the bookkeeping needed to read results back. */
    static final class Netlist {
        final List<String> lines = new ArrayList<>();
        final Map<CircuitNode, String> nodeNames = new IdentityHashMap<>();
        final Map<CircuitEdge, String> ammeters = new IdentityHashMap<>();
        final Map<Capacitor, String[]> capacitorTerminals = new IdentityHashMap<>();
    }

    /**
     * Solves one tick of {@code dt} seconds. On {@link Result#OK} every node voltage, edge current
     * and capacitor charge has been written into the circuit's variables.
     */
    public static Result solve(Collection<CircuitNode> nodes, Collection<CircuitEdge> edges,
                               Collection<CircuitComponent> components, double dt) {
        NgSpice ng = NgSpice.get();
        if (ng == null || ng.isFatal()) return Result.UNSUPPORTED;
        Netlist net;
        try {
            net = build(nodes, edges, components, dt);
        } catch (UnsupportedElement e) {
            log.warn("ngspice backend cannot model {}; using the built-in solver", e.getMessage());
            return Result.UNSUPPORTED;
        }
        if (net == null) { // nothing to solve: isolated nodes are their own ground, no current anywhere
            for (CircuitNode n : nodes) n.getVoltage().setValue(0.0);
            for (CircuitEdge e : edges) e.getCurrent().setValue(0.0);
            return Result.OK;
        }

        if (!ng.loadCircuit(net.lines) || !ng.command("run")) {
            log.warn("ngspice solve failed: {}", ng.drainErrors());
            return Result.FAILED;
        }
        Double t = ng.lastValue("time");
        if (t == null || Math.abs(t - dt) > dt * 1e-6) {
            log.warn("ngspice transient did not reach the tick end (t={}): {}", t, ng.drainErrors());
            return Result.FAILED;
        }
        for (Map.Entry<CircuitNode, String> e : net.nodeNames.entrySet()) {
            String name = e.getValue();
            double v = name.equals("0") ? 0.0 : valueOr(ng, "v(" + name + ")", 0.0);
            e.getKey().getVoltage().setValue(v);
        }
        for (Map.Entry<CircuitEdge, String> e : net.ammeters.entrySet()) {
            e.getKey().getCurrent().setValue(valueOr(ng, "i(" + e.getValue() + ")", 0.0));
        }
        for (Map.Entry<Capacitor, String[]> e : net.capacitorTerminals.entrySet()) {
            String[] term = e.getValue();
            double va = term[0].equals("0") ? 0.0 : valueOr(ng, "v(" + term[0] + ")", 0.0);
            double vb = term[1].equals("0") ? 0.0 : valueOr(ng, "v(" + term[1] + ")", 0.0);
            Informations.CapacitorInfo info = e.getKey().get();
            e.getKey().setSolvedCharge(info.getCapacitance() * (va - vb));
        }
        // Voltages of nodes with no edge at all: ngspice never saw them; they are their own ground.
        for (CircuitNode n : nodes) if (!net.nodeNames.containsKey(n)) n.getVoltage().setValue(0.0);
        return Result.OK;
    }

    private static double valueOr(NgSpice ng, String vec, double fallback) {
        Double v = ng.lastValue(vec);
        return v == null || v.isNaN() ? fallback : v;
    }

    static final class UnsupportedElement extends Exception {
        UnsupportedElement(String what) { super(what); }
    }

    /** Builds the netlist; visible for tests. Returns {@code null} when there is nothing to solve. */
    static Netlist build(Collection<CircuitNode> nodes, Collection<CircuitEdge> edges,
                         Collection<CircuitComponent> components, double dt) throws UnsupportedElement {
        Netlist net = new Netlist();
        int nodeIdx = 0;
        for (CircuitNode n : nodes) {
            net.nodeNames.put(n, n.isGrounded() ? "0" : "n" + (nodeIdx++));
        }
        // Collector edges owned by a transistor are controlled sources, not free edges.
        Map<CircuitEdge, BJTransistor> collectorOf = new IdentityHashMap<>();
        for (CircuitComponent c : components) {
            if (c instanceof BJTransistor bjt) {
                if (bjt.getEdgeCollector() != null) collectorOf.put(bjt.getEdgeCollector(), bjt);
            } else if (c != null && !(c.getClass() == CircuitComponent.class)) {
                throw new UnsupportedElement(c.getClass().getSimpleName());
            }
        }

        List<String> body = new ArrayList<>();
        int idx = 0;
        boolean anyDevice = false;
        // First pass: ammeter names, so controlled sources can reference their sensing ammeter.
        for (CircuitEdge e : edges) {
            if (!e.isConnected()) continue;
            net.ammeters.put(e, "vm" + (idx++));
        }
        for (CircuitEdge e : edges) {
            if (!e.isConnected()) continue;
            String s = net.nodeNames.get(e.getStart()), t = net.nodeNames.get(e.getEnd());
            String vm = net.ammeters.get(e);
            String mid = "m_" + vm;
            String id = vm.substring(2);
            if (e instanceof Wire && !collectorOf.containsKey(e)) {
                body.add(vm + " " + s + " " + t + " dc 0");
            } else if (e instanceof Resistor r) {
                String end = series(body, id, s, mid, r.get().getResistance());
                body.add(vm + " " + end + " " + t + " dc 0");
            } else if (e instanceof Battery b) {
                Informations.BatteryInfo info = b.get();
                String mid2 = mid + "b";
                body.add("v" + id + " " + s + " " + mid2 + " dc " + num(info.getVoltage()));
                String end = series(body, id, mid2, mid, info.getResistance());
                body.add(vm + " " + end + " " + t + " dc 0");
            } else if (e instanceof Capacitor c) {
                Informations.CapacitorInfo info = c.get();
                double v0 = info.getCharge() / info.getCapacitance();
                String mid2 = mid + "c";
                body.add("c" + id + " " + s + " " + mid2 + " " + num(info.getCapacitance()) + " ic=" + num(v0));
                String end = series(body, id, mid2, mid, info.getInternalResistance());
                body.add(vm + " " + end + " " + t + " dc 0");
                net.capacitorTerminals.put(c, new String[]{s, mid2});
            } else if (e instanceof Diode d) {
                Informations.DiodeInfo info = d.get();
                // Piecewise resistance: forward for V_start > V_end, reverse otherwise, blended over ~1 mV.
                body.add("r" + id + " " + s + " " + mid + " r='" + num(info.getForwardResistance()) + "+("
                        + num(info.getReverseResistance()) + "-" + num(info.getForwardResistance())
                        + ")*(1-tanh(v(" + s + "," + mid + ")*1000))/2'");
                body.add(vm + " " + mid + " " + t + " dc 0");
            } else if (collectorOf.containsKey(e)) {
                BJTransistor bjt = collectorOf.get(e);
                String sense = net.ammeters.get(bjt.getEdgeBase());
                if (sense == null) throw new UnsupportedElement("transistor without a connected base edge");
                body.add("f" + id + " " + s + " " + mid + " " + sense + " " + num(bjt.getInfo().getBeta()));
                body.add(vm + " " + mid + " " + t + " dc 0");
            } else {
                throw new UnsupportedElement(e.getClass().getSimpleName());
            }
            anyDevice = true;
        }
        if (!anyDevice) return null;

        net.lines.add("circuitslib tick");
        net.lines.addAll(body);
        // Nodes that ngspice would otherwise see as floating (only capacitors / current sources attached)
        // get a huge leak to ground so the DC/UIC start is well posed. It is far below any device value.
        net.lines.add(".options rshunt=1e12 reltol=1e-6 abstol=1e-12 vntol=1e-9 chgtol=1e-16");
        // One tick, adaptive step with error control; print step = a fine fraction of the tick.
        net.lines.add(".tran " + num(dt / 50) + " " + num(dt) + " uic");
        net.lines.add(".end");
        return net;
    }

    /** Below this, a series resistance is treated as an ideal short (keeps the matrix well conditioned). */
    private static final double SHORT_OHMS = 1e-6;

    /** Emits {@code r<id> a b R} unless R is negligible, in which case it returns b renamed to a (no device). */
    private static String series(List<String> body, String id, String a, String b, double ohms) {
        if (ohms < SHORT_OHMS) return a;
        body.add("r" + id + " " + a + " " + b + " " + num(ohms));
        return b;
    }

    private static String num(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) v = Informations.LARGE;
        return String.format(Locale.ROOT, "%.12g", v);
    }
}
