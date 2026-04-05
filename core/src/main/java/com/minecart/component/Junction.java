package com.minecart.component;

import com.minecart.action.ActionTypes;
import com.minecart.action.Actions;
import com.minecart.logic.CircuitEdge;
import com.minecart.logic.CircuitNode;
import com.minecart.foundation.World;
import com.minecart.registry.AllComponents;
import com.minecart.variant.ElectricalVariate;
import com.minecart.variant.type.Informations;

/**
 * A circuit node that only allows limited amount of connection.
 */
public class Junction extends CircuitNode implements ElectricalVariate<Informations.JunctionInfo> {
    Informations.JunctionInfo info;

    public Junction(World world) {
        super(world);
        this.info = getDefault();
    }

    @Override
    public boolean connectEdge(CircuitEdge egde, boolean simulate) {
        if(getConnection().size() + 1 > info.getConnection())
            return false;
        return super.connectEdge(egde, simulate);
    }

    @Override
    public Informations.JunctionInfo get() {
        return info;
    }

    @Override
    public Informations.JunctionInfo getDefault() {
        return new Informations.JunctionInfo(0);
    }

    @Override
    public boolean hasProperty(int index) {
        return false;
    }

    @Override
    public Object getProperty(int index) {
        return null;
    }

    protected void handleConnection(Actions.SetConnectionAction action){
        info.setConnection(action.getValue());
    }

    static {
        AllComponents.JUNCTION.addActionHandler(ActionTypes.SET_CONNECTION, (circuitNode, action) -> circuitNode.handleConnection(action));
    }
}
