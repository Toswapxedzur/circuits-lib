package com.minecart.elements.node;

import com.minecart.action.ActionTypes;
import com.minecart.action.Actions;
import com.minecart.logic.CircuitEdge;
import com.minecart.logic.CircuitNode;
import com.minecart.foundation.World;
import com.minecart.registry.AllComponents;
import com.minecart.ui.panel.InfoPanelElementType;
import com.minecart.ui.panel.InfoPanelRegistry;
import com.minecart.ui.panel.InfoPanelTypes;
import com.minecart.ui.panel.PanelFieldKey;
import com.minecart.ui.panel.fields.NumberFieldSpec;
import com.minecart.variant.ElectricalVariate;
import com.minecart.variant.Informations;

import java.util.Objects;

/**
 * A circuit node that only allows limited amount of connection.
 */
public class Junction extends CircuitNode implements ElectricalVariate<Informations.JunctionInfo> {
    Informations.JunctionInfo info;

    public static final InfoPanelElementType<Junction> PANEL_TYPE =
            new InfoPanelElementType<>("junction", Junction.class, InfoPanelTypes.NODE);
    public static final PanelFieldKey<Double> FIELD_MAX_CONNECTIONS =
            PanelFieldKey.doubleKey("junction:maxConnections");

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

    @Override
    public void set(Informations.JunctionInfo property) {
        this.info = Objects.requireNonNull(property, "property");
    }

    @Override
    public void set(int index, Object property) {
        if (index != 0) {
            throw new IllegalArgumentException("Unknown property index: " + index);
        }
        if (!(property instanceof Integer i)) {
            throw new IllegalArgumentException("Expected Integer for max connections, got " + property);
        }
        info.setConnection(i);
    }

    protected void handleConnection(Actions.SetConnectionAction action){
        info.setConnection(action.getValue());
    }

    static {
        AllComponents.JUNCTION.addActionHandler(ActionTypes.SET_CONNECTION, (circuitNode, action) -> circuitNode.handleConnection(action));
        InfoPanelRegistry.bind(AllComponents.JUNCTION, PANEL_TYPE);
        InfoPanelRegistry.registerPanel(PANEL_TYPE, (junction, builder) ->
                builder.add(new NumberFieldSpec(FIELD_MAX_CONNECTIONS, "Max Connections",
                                junction.info.getConnection()),
                        (j, ctx) -> ctx.doubleValue(FIELD_MAX_CONNECTIONS)
                                .filter(v -> Double.isFinite(v) && v >= 0.0)
                                .ifPresent(v -> {
                                    j.info.setConnection((int) Math.round(v));
                                    ctx.markChanged(j);
                                })));
    }
}
