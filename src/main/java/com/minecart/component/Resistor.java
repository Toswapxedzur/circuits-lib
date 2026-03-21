package com.minecart.component;

import com.minecart.behaviour.ElectricalVariate;
import com.minecart.behaviour.type.ResistorInformation;
import com.minecart.math.function.Expression;
import com.minecart.misc.ElectricalVariable;

import static com.minecart.math.function.Expression.ExpressionBuilder.*;

import java.util.List;

public class Resistor<T extends ResistorInformation> extends TwoConnector implements ElectricalVariate<T> {

    protected T info;

    public Resistor() {
        this.setDefault();
    }

    public Resistor(T info) {
        this.info = info;
    }

    public double getResistance() {
        return this.info.resistance;
    }

    public void setResistance(double resistance) {
        this.info.resistance = resistance;
    }

    // =========================================
    // Variate System
    // =========================================

    @Override
    public void set(T argument) {
        this.info = argument;
    }

    @Override
    public T get() {
        return this.info;
    }

    @Override
    public T getDefault() {
        return null;
    }

    @Override
    public boolean hasProperty(int index) {
        return index == 0;
    }

    @Override
    public Object getProperty(int index) {
        return switch (index) {
            default -> this.info.resistance;
        };
    }

    @Override
    public void collectRule(List<Expression> equations) {
        super.collectRule(equations);

        if(edges.size() != 2)
            return;
        ElectricalVariable current = edges.get(0).getCurrent();
        ElectricalVariable voltage1 = edges.get(0).getVoltage();
        ElectricalVariable voltage2 = edges.get(1).getVoltage();
        Expression toCurrent = edges.get(0).shouldRevert(this) ? var(current) : neg(var(current));
        Expression expression = sub(mul(toCurrent, val(getResistance())), sub(var(voltage1), var(voltage2)));
        equations.add(expression);
    }
}
