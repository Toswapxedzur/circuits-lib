package com.minecart.component;

import com.minecart.action.ActionTypes;
import com.minecart.action.Actions;
import com.minecart.math.function.ContinuousVariable;
import com.minecart.variant.ElectricalVariate;
import com.minecart.variant.type.ResistorInformation;
import com.minecart.math.function.Expression;

import static com.minecart.math.function.Expression.ExpressionBuilder.*;

import java.util.List;

public class Resistor<T extends ResistorInformation> extends TwoConnector implements ElectricalVariate<T> {

    protected T info;

    public Resistor() {
        this.setDefault();
        addActionHandler(ActionTypes.SET_RESISTANCE, this::handleResistanceChange);
    }

    public Resistor(T info) {
        this.info = info;
        addActionHandler(ActionTypes.SET_RESISTANCE, this::handleResistanceChange);
    }

    private void handleResistanceChange(Actions.SetResistanceAction action) {
        double currentRes = this.getResistance();

        double newRes = action.getOperator().applyAsDouble(currentRes);

        this.setResistance(newRes);
    }

    public double getResistance() {
        return this.info.resistance;
    }

    public void setResistance(double resistance) {
        this.info.resistance = resistance;
    }

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
        ContinuousVariable<Double> current = edges.get(0).getCurrent();
        ContinuousVariable<Double> voltage1 = edges.get(0).getVoltage();
        ContinuousVariable<Double> voltage2 = edges.get(1).getVoltage();
        Expression toCurrent = edges.get(0).shouldRevert(this) ? variable(current) : neg(variable(current));
        Expression expression = sub(mul(toCurrent, value(getResistance())), sub(variable(voltage1), variable(voltage2)));
        equations.add(expression);
    }
}
