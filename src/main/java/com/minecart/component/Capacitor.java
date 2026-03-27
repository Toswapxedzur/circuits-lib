package com.minecart.component;

import com.minecart.action.ActionTypes;
import com.minecart.action.Actions;
import com.minecart.logic.CircuitEdge;
import com.minecart.logic.ServerWorld;
import com.minecart.math.function.LinearSystem;
import com.minecart.registry.AllComponents;
import com.minecart.variant.ElectricalVariate;
import com.minecart.variant.type.Informations.*;

public class Capacitor extends CircuitEdge implements ElectricalVariate<CapacitorInfo> {
    protected CapacitorInfo info;

    public Capacitor(ServerWorld world) {
        super(world);
        info = getDefault();
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

        if (!isConnected() || get() == null) return;

        double tickRate = getWorld().getTickRate();
        double deltaCharge = getCurrent().getValue() * tickRate;

        get().setCharge(get().getCharge() + deltaCharge);
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

    protected void handleCapacitance(Actions.SetCapacitanceAction action) {
        info.setCapacitance(action.getOperator().applyAsDouble(info.getCapacitance()));
    }

    protected void handleResistance(Actions.SetResistanceAction action) {
        info.setInternalResistance(action.getOperator().applyAsDouble(info.getInternalResistance()));
    }

    static {
        AllComponents.CAPACITOR.addActionHandler(ActionTypes.SET_CAPACITANCE, (capacitor, action) -> capacitor.handleCapacitance(action));
        AllComponents.CAPACITOR.addActionHandler(ActionTypes.SET_RESISTANCE, (capacitor, setResistanceAction) -> capacitor.handleResistance(setResistanceAction));
    }
}
