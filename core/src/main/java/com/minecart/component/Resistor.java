package com.minecart.component;

import com.minecart.action.ActionTypes;
import com.minecart.action.Actions;
import com.minecart.logic.CircuitEdge;
import com.minecart.logic.World;
import com.minecart.registry.AllComponents;
import com.minecart.variant.ElectricalVariate;
import com.minecart.variant.type.Informations.ResistorInfo;
import com.minecart.math.LinearSystem.RelationProvider;

/**
 * An Ohmic resistor
 */
public class Resistor extends CircuitEdge implements ElectricalVariate<ResistorInfo> {

    protected ResistorInfo info;

    public Resistor(World world) {
        super(world);
        this.info = getDefault();
    }

    @Override
    public void collectRule(RelationProvider equations) {
        super.collectRule(equations);

        if(!isConnected())
            return;

        // Ohm's Law: V_start - V_end - (I * R) = 0
        equations.stampCoefficient(getStart().getVoltage(), 1.0);
        equations.stampCoefficient(getEnd().getVoltage(), -1.0);

        // Multiply current by negative resistance to balance the equation to 0
        equations.stampCoefficient(getCurrent(), -info.getResistance());

        equations.stampConstant(0.0);

        equations.endRelation();
    }

    @Override
    public ResistorInfo get() {
        return this.info;
    }

    @Override
    public ResistorInfo getDefault() {
        // Default to a standard 10.0 Ohm resistor
        return new ResistorInfo(1.0);
    }

    @Override
    public boolean hasProperty(int index) {
        // Index 0 represents the Resistance property
        return index == 0;
    }

    @Override
    public Object getProperty(int index) {
        if (index == 0 && info != null) {
            return info.getResistance();
        }
        return null;
    }

    protected void handleResistance(Actions.SetResistanceAction action) {
        info.setResistance(action.getValue());
    }

    static {
        AllComponents.RESISTOR.addActionHandler(ActionTypes.SET_RESISTANCE, (resistor, action) -> resistor.handleResistance(action));
    }
}