package com.minecart.elements.edge;

import com.minecart.action.ActionTypes;
import com.minecart.action.Actions;
import com.minecart.logic.CircuitEdge;
import com.minecart.foundation.World;
import com.minecart.registry.AllComponents;
import com.minecart.ui.panel.InfoPanelElementType;
import com.minecart.ui.panel.InfoPanelRegistry;
import com.minecart.ui.panel.InfoPanelTypes;
import com.minecart.ui.panel.PanelFieldKey;
import com.minecart.ui.panel.fields.NumberFieldSpec;
import com.minecart.variant.ElectricalVariate;
import com.minecart.variant.Informations.BatteryInfo;
import com.minecart.math.LinearSystem.RelationProvider;

import java.util.Objects;

/**
 * A non-fully-ideal battery, use extreme small internal resistance for near ideal performance
 */
public class Battery extends CircuitEdge implements ElectricalVariate<BatteryInfo> {

    protected BatteryInfo info;

    public static final InfoPanelElementType<Battery> PANEL_TYPE =
            new InfoPanelElementType<>("battery", Battery.class, InfoPanelTypes.EDGE);
    public static final PanelFieldKey<Double> FIELD_VOLTAGE =
            PanelFieldKey.doubleKey("battery:voltage");
    public static final PanelFieldKey<Double> FIELD_RESISTANCE =
            PanelFieldKey.doubleKey("battery:resistance");

    public Battery(World world) {
        super(world);
        this.info = getDefault();
    }

    @Override
    public void collectRule(RelationProvider equations) {
        super.collectRule(equations);

        if (!isConnected() || info == null) return;

        equations.stampCoefficient(getStart().getVoltage(), 1.0);
        equations.stampCoefficient(getEnd().getVoltage(), -1.0);

        // Internal resistance causes a voltage drop proportional to the current
        equations.stampCoefficient(getCurrent(), -get().getResistance());

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

    @Override
    public void set(BatteryInfo property) {
        this.info = Objects.requireNonNull(property, "property");
    }

    @Override
    public void set(int index, Object property) {
        if (index < 0 || index > 1) {
            throw new IllegalArgumentException("Unknown property index: " + index);
        }
        if (!(property instanceof Number n)) {
            throw new IllegalArgumentException("Expected Number, got " + property);
        }
        if (index == 0) {
            info.setVoltage(n.doubleValue());
        } else {
            info.setResistance(n.doubleValue());
        }
    }

    protected void handleResistance(Actions.SetResistanceAction action) {
        info.setResistance(action.getValue());
    }

    protected void handleVoltage(Actions.SetVoltageAction action) {
        info.setVoltage(action.getValue());
    }

    static {
        AllComponents.BATTERY.addActionHandler(ActionTypes.SET_RESISTANCE, (battery, action) -> battery.handleResistance(action));
        AllComponents.BATTERY.addActionHandler(ActionTypes.SET_VOLTAGE, (battery, action) -> battery.handleVoltage(action));
        InfoPanelRegistry.bind(AllComponents.BATTERY, PANEL_TYPE);
        InfoPanelRegistry.registerPanel(PANEL_TYPE, (battery, builder) -> {
            builder.add(new NumberFieldSpec(FIELD_VOLTAGE, "Voltage", battery.info.getVoltage()),
                    (b, ctx) -> ctx.doubleValue(FIELD_VOLTAGE)
                            .filter(Double::isFinite)
                            .ifPresent(v -> {
                                b.info.setVoltage(v);
                                ctx.markChanged(b);
                            }));
            builder.add(new NumberFieldSpec(FIELD_RESISTANCE, "Internal Resistance", battery.info.getResistance()),
                    (b, ctx) -> ctx.doubleValue(FIELD_RESISTANCE)
                            .filter(v -> Double.isFinite(v) && v > 0.0)
                            .ifPresent(v -> {
                                b.info.setResistance(v);
                                ctx.markChanged(b);
                            }));
        });
    }
}