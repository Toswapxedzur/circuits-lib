package com.minecart.component;

import com.minecart.logic.CircuitEdge;
import com.minecart.logic.World;
import com.minecart.math.function.DoubleVar;
import com.minecart.math.function.LinearSystem;
import com.minecart.variant.ElectricalVariate;
import com.minecart.variant.type.Informations.*;

import java.util.Set;

public class Capacitor extends CircuitEdge implements ElectricalVariate<CapacitorInfo> {
    protected CapacitorInfo info;

    public Capacitor(World world) {
        super(world);
        setDefault();
    }

    @Override
    public void collectRule(LinearSystem.RelationProvider equations) {
        super.collectRule(equations);

        if(!isConnected())
            return;

        double voltage = get().getCharge() / get().getCapacitance();

        equations.stampCoefficient(getStart().getVoltage(), 1.0);
        equations.stampCoefficient(getEnd().getVoltage(), -1.0);

        equations.stampCoefficient(getCurrent(), get().getInternalResistance());

        equations.stampConstant(voltage);

        equations.endRelation();
    }

    @Override
    public void tick() {
        super.tick();
        //change the capacitance
    }

    @Override
    public void set(CapacitorInfo argument) {
        this.info = argument;
    }

    @Override
    public CapacitorInfo get() {
        return info;
    }

    @Override
    public CapacitorInfo getDefault() {
        return new CapacitorInfo(1, 1e-9);
    }

    @Override
    public boolean hasProperty(int index) {
        return index >= 0 && index <= 2;
    }

    @Override
    public Object getProperty(int index) {
        return switch (index){
            case 0 -> info.getCapacitance();
            case 1 -> info.getCharge();
            default -> info.getInternalResistance();
        };
    }
}
