package com.minecart.component;

import com.minecart.action.ActionTypes;
import com.minecart.action.Actions;
import com.minecart.logic.CircuitEdge;
import com.minecart.logic.ServerWorld;
import com.minecart.registry.AllComponents;
import com.minecart.variant.ElectricalVariate;
import com.minecart.variant.type.Informations.BatteryInfo;
import com.minecart.math.function.LinearSystem.RelationProvider;

/**
 * A non-fully-ideal battery, use extreme small internal resistance for near ideal performance
 */
public class Battery extends CircuitEdge implements ElectricalVariate<BatteryInfo> {

    protected BatteryInfo info;

    public Battery(ServerWorld world) {
        super(world);
        this.info = getDefault();
    }

    @Override
    public void collectRule(RelationProvider equations) {
        super.collectRule(equations);

        if (getStart() == null || getEnd() == null || info == null) return;

        equations.stampCoefficient(getStart().getVoltage(), 1.0);
        equations.stampCoefficient(getEnd().getVoltage(), -1.0);

        // Internal resistance causes a voltage drop proportional to the current
        equations.stampCoefficient(getCurrent(), get().getResistance());

        // The target constant is the battery's rated electromotive force (EMF)
        equations.stampConstant(info.getVoltage());

        equations.endRelation();
    }

    @Override
    public BatteryInfo get() {
        return this.info;
    }

    @Override
    public BatteryInfo getDefault() {
        return new BatteryInfo(1.0, 1e-9);
    }

    @Override
    public boolean hasProperty(int index) {
        return index <= 1 && index >= 0;
    }

    @Override
    public Object getProperty(int index) {
        if (info != null) {
            return switch (index){
                case 0 -> info.getVoltage();
                default -> info.getResistance();
            };
        }
        return null;
    }

    protected void handleResistance(Actions.SetResistanceAction action) {
        info.setResistance(action.getOperator().applyAsDouble(info.getResistance()));
    }

    protected void handleVoltage(Actions.SetVoltageAction action) {
        info.setVoltage(action.getOperator().applyAsDouble(info.getVoltage()));
    }

    static {
        AllComponents.BATTERY.addActionHandler(ActionTypes.SET_RESISTANCE, (battery, action) -> battery.handleResistance(action));
        AllComponents.BATTERY.addActionHandler(ActionTypes.SET_VOLTAGE, (battery, action) -> battery.handleVoltage(action));
    }
}