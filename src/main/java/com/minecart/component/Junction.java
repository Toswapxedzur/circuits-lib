package com.minecart.component;

import com.minecart.action.ActionTypes;
import com.minecart.action.Actions;
import com.minecart.logic.CircuitEdge;
import com.minecart.logic.CircuitNode;
import com.minecart.logic.World;
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
    public void set(Informations.JunctionInfo argument) {

    }

    @Override
    public Informations.JunctionInfo get() {
        return null;
    }

    @Override
    public Informations.JunctionInfo getDefault() {
        return null;
    }

    @Override
    public boolean hasProperty(int index) {
        return false;
    }

    @Override
    public Object getProperty(int index) {
        return null;
    }

    public void handleConnection(Actions.SetConnectionAction action){
        info.setConnection(action.getOperator().applyAsInt(info.getConnection()));
    }
}
