package com.minecart.elements.edge;

import com.minecart.foundation.World;
import com.minecart.logic.CircuitEdge;

/**
 * Ideal wire: {@code V_start - V_end = 0}. Used for bare branch modeling (registry ids {@code circuit_edge},
 * {@code perfect_wire}, {@code normal_wire}). The electrical behaviour is emitted to ngspice by
 * {@link com.minecart.spice.SpiceSolver} (a wire becomes the branch ammeter alone).
 */
public class Wire extends CircuitEdge {

    public Wire(World world) {
        super(world);
    }

    /** An ideal wire = just the branch ammeter (V_start = V_end, current = i(vm)). */
    @Override
    public void emitSpice(com.minecart.spice.SpiceContext ctx) {
        ctx.ammeterFrom(ctx.start());
    }
}
