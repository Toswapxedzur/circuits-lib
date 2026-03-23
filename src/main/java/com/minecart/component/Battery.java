package com.minecart.component;

import com.minecart.action.ActionTypes;
import com.minecart.action.Actions;
import com.minecart.logic.CircuitEdge;
import com.minecart.math.function.ContinuousVariable;
import com.minecart.math.function.Expression;
import com.minecart.variant.ElectricalVariate;
import com.minecart.variant.type.BatteryInformation;

import java.util.List;

import static com.minecart.math.function.Expression.ExpressionBuilder.*;

public class Battery<T extends BatteryInformation> extends TwoConnector implements ElectricalVariate<T> {
    protected T info;

    public Battery() {
        setDefault();
        addActionHandler(ActionTypes.SET_VOLTAGE, this::handleVoltageChange);
    }

    protected Battery(T info) {
        this.info = info;
        addActionHandler(ActionTypes.SET_VOLTAGE, this::handleVoltageChange);
    }

    public double getVoltage() {
        return this.info.voltage;
    }

    protected void setVoltage(double voltage) {
        this.info.voltage = voltage;
    }

    public double getInternalResistance() {
        return this.info.internalResistance;
    }

    protected void setInternalResistance(double internalResistance) {
        this.info.internalResistance = internalResistance;
    }

    private void handleVoltageChange(Actions.SetVoltageAction action) {
        double currentVolt = this.getVoltage();

        double newVolt = action.getOperator().applyAsDouble(currentVolt);

        this.setVoltage(newVolt);
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
        return (T) new BatteryInformation(1, 1);
    }

    @Override
    public boolean hasProperty(int index) {
        return index < 2;
    }

    @Override
    public Object getProperty(int index) {
        return switch (index){
            case 0 -> this.info.voltage;
            default -> this.info.internalResistance;
        };
    }

    @Override
    public void collectRule(List<Expression> equations) {
        super.collectRule(equations); // Enforces KCL

        if(edges.size() != 2) return;

        CircuitEdge edge1 = edges.get(0);
        CircuitEdge edge2 = edges.get(1);

        ContinuousVariable<Double> voltage1 = edge1.getVoltage();
        ContinuousVariable<Double> voltage2 = edge2.getVoltage();

        boolean edge1IsPositive = !edge1.shouldRevert(this);

        Expression vDiff;
        Expression currentOut;

        if (edge1IsPositive) {
            vDiff = sub(variable(voltage1), variable(voltage2));
            currentOut = edge1.shouldRevert(this) ? neg(variable(edge1.getCurrent())) : variable(edge1.getCurrent());
        } else {
            vDiff = sub(variable(voltage2), variable(voltage1));
            currentOut = edge2.shouldRevert(this) ? neg(variable(edge2.getCurrent())) : variable(edge2.getCurrent());
        }

        Expression internalDrop = mul(currentOut, value(getInternalResistance()));
        Expression totalVoltage = add(vDiff, internalDrop);
        equations.add(sub(totalVoltage, value(getVoltage())));
    }
}
