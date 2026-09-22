package com.minecart.spice;

import com.minecart.elements.edge.Capacitor;
import com.minecart.logic.CircuitEdge;

import java.util.List;

/**
 * The seam a circuit element uses to emit its own SPICE netlist lines — see {@link CircuitEdge#emitSpice}. It
 * lets each device's SPICE model live on the device (Resistor emits its R, Battery its EMF, …) instead of a
 * central {@code instanceof} switch in {@link SpiceSolver}. One instance is built per edge, carrying that
 * edge's SPICE node names ({@code start}/{@code end}), its 0 V ammeter, and a scratch {@code mid} node prefix.
 */
public final class SpiceContext {
    /** Thrown by an element with no SPICE model → the whole circuit is unsupported for ngspice this tick. */
    public static final class Unsupported extends Exception {
        public Unsupported(String what) { super(what); }
    }

    private final List<String> body;
    private final SpiceSolver.Netlist net;
    private final CircuitEdge edge;
    private final String s, t, vm, mid, id;

    SpiceContext(List<String> body, SpiceSolver.Netlist net, CircuitEdge edge, String s, String t, String vm) {
        this.body = body;
        this.net = net;
        this.edge = edge;
        this.s = s;
        this.t = t;
        this.vm = vm;
        this.mid = "m_" + vm;
        this.id = vm.substring(2);
    }

    /** SPICE node name of the edge's start / end. */
    public String start() { return s; }
    public String end() { return t; }
    /** A scratch internal node prefix unique to this edge (append a suffix for a second mid node). */
    public String mid() { return mid; }
    /** This edge's unique id (used to name its SPICE devices). */
    public String id() { return id; }

    /** Appends a raw netlist line. */
    public void line(String spiceLine) { body.add(spiceLine); }

    /** Emits this edge's 0 V "ammeter" from {@code from} to the end node — its branch current is {@code i(vm)}. */
    public void ammeterFrom(String from) { body.add(vm + " " + from + " " + t + " dc 0"); }

    /** Emits a series resistor {@code r<id> a b ohms} unless negligible (then an ideal short); returns the far
     *  node to continue the branch from. */
    public String series(String a, String b, double ohms) { return SpiceSolver.series(body, id, a, b, ohms); }

    /** Records this (capacitor) edge's two terminals so the solver reads its charge back after the tick. */
    public void capacitorTerminals(String termA, String termB) {
        net.capacitorTerminals.put((Capacitor) edge, new String[]{termA, termB});
    }

    /** Formats a value for a netlist (finite, locale-independent). */
    public static String num(double v) { return SpiceSolver.num(v); }
}
