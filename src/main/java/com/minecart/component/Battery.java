package com.minecart.component;

import com.minecart.behaviour.ElectricalVariate;
import com.minecart.behaviour.type.BatteryInformation;
import com.minecart.math.function.Expression;
import com.minecart.misc.ElectricalVariable;

import java.util.List;

import static com.minecart.math.function.Expression.ExpressionBuilder.*;

public class Battery<T extends BatteryInformation> extends TwoConnector implements ElectricalVariate<T> {
    protected T info;

    public Battery() {
        setDefault();
    }

    public Battery(T info) {
        this.info = info;
    }

    public double getVoltage() {
        return this.info.voltage;
    }

    public void setVoltage(double voltage) {
        this.info.voltage = voltage;
    }

    public double getInternalResistance() {
        return this.info.internalResistance;
    }

    public void setInternalResistance(double internalResistance) {
        this.info.internalResistance = internalResistance;
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

        Expression internalDrop = mul(currentOut, val(getInternalResistance()));
        Expression totalVoltage = add(vDiff, internalDrop);
        equations.add(sub(totalVoltage, val(getVoltage())));
    }
}
