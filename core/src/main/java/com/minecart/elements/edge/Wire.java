package com.minecart.elements.edge;

import com.minecart.foundation.World;
import com.minecart.logic.CircuitEdge;
import com.minecart.math.LinearSystem.RelationProvider;

/**
 * Ideal wire: {@code V_start - V_end = 0}. Used for bare branch modeling (registry ids {@code circuit_edge},
 * {@code perfect_wire}, {@code normal_wire}).
 */
public class Wire extends CircuitEdge {

    public Wire(World world) {
        super(world);
    }

    @Override
    public void collectRule(RelationProvider equations) {
        super.collectRule(equations);

        if (!isConnected()) return;

        equations.stampCoefficient(getStart().getVoltage(), 1.0);
        equations.stampCoefficient(getEnd().getVoltage(), -1.0);
        equations.stampConstant(0.0);
        equations.endRelation();
    }
}
