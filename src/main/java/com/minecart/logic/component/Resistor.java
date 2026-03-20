package com.minecart.logic.component;

import com.minecart.math.function.Expression;
import com.minecart.misc.ElectricalVariable;

import static com.minecart.math.function.Expression.ExpressionBuilder.*;

import java.util.List;

public class Resistor extends TwoConnector{
    public Resistor(){
    }

    //replaced forced assignment later with a new behaviour system
    Double resistance = 10.0;

    @Override
    public void collectRule(List<Expression> equations) {
        super.collectRule(equations);

        if(edges.size() != 2)
            return;
        ElectricalVariable current = edges.get(0).getCurrent();
        ElectricalVariable voltage1 = edges.get(0).getVoltage();
        ElectricalVariable voltage2 = edges.get(1).getVoltage();
        Expression toCurrent = edges.get(0).shouldRevert(this) ? var(current) : neg(var(current));
        Expression expression = sub(mul(toCurrent, val(resistance)), sub(var(voltage1), var(voltage2)));
        equations.add(expression);
    }
}
