package com.minecart.logic.component;

import com.minecart.math.function.Expression;
import com.minecart.misc.ElectricalVariable;

import java.util.List;

import static com.minecart.math.function.Expression.ExpressionBuilder.*;

public class Battery extends TwoConnector{
    public Battery(){
    }

    //replaced later
    Double volt = 20.0;
    Double internalResistance = 1e-9;

    @Override
    public void collectRule(List<Expression> equations) {
        super.collectRule(equations); // Enforces KCL

        if(edges.size() != 2) return;

        CircuitEdge edge1 = edges.get(0);
        CircuitEdge edge2 = edges.get(1);

        ElectricalVariable voltage1 = edge1.getVoltage();
        ElectricalVariable voltage2 = edge2.getVoltage();

        boolean edge1IsPositive = !edge1.shouldRevert(this);

        Expression vDiff;
        Expression currentOut;

        if (edge1IsPositive) {
            vDiff = sub(var(voltage1), var(voltage2));
            currentOut = edge1.shouldRevert(this) ? neg(var(edge1.getCurrent())) : var(edge1.getCurrent());
        } else {
            vDiff = sub(var(voltage2), var(voltage1));
            currentOut = edge2.shouldRevert(this) ? neg(var(edge2.getCurrent())) : var(edge2.getCurrent());
        }

        Expression internalDrop = mul(currentOut, val(internalResistance));
        Expression totalVoltage = add(vDiff, internalDrop);
        equations.add(sub(totalVoltage, val(volt)));
    }
}
