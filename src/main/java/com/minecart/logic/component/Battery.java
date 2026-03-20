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

    @Override
    public void collectRule(List<Expression> equations) {
        super.collectRule(equations);

        if(edges.size() != 2)
            return;
        ElectricalVariable voltage1 = edges.get(0).getVoltage();
        ElectricalVariable voltage2 = edges.get(1).getVoltage();
        Expression expression = sub(sub(var(voltage1), var(voltage2)), val(volt));
        equations.add(expression);
    }
}
