package com.minecart.elements.edge;

import com.minecart.action.ActionTypes;
import com.minecart.action.Actions;
import com.minecart.logic.CircuitEdge;
import com.minecart.foundation.World;
import com.minecart.math.LinearSystem;
import com.minecart.registry.AllComponents;
import com.minecart.serialization.tag.CompoundTag;
import com.minecart.ui.panel.InfoPanelElementType;
import com.minecart.ui.panel.InfoPanelRegistry;
import com.minecart.ui.panel.InfoPanelTypes;
import com.minecart.ui.panel.PanelFieldKey;
import com.minecart.ui.panel.fields.NumberFieldSpec;
import com.minecart.variant.ElectricalVariate;
import com.minecart.variant.Informations.*;

import java.util.Objects;

public class Capacitor extends CircuitEdge implements ElectricalVariate<CapacitorInfo> {
    protected CapacitorInfo info;

    public static final InfoPanelElementType<Capacitor> PANEL_TYPE =
            new InfoPanelElementType<>("capacitor", Capacitor.class, InfoPanelTypes.EDGE);
    public static final PanelFieldKey<Double> FIELD_CAPACITANCE =
            PanelFieldKey.doubleKey("capacitor:capacitance");
    public static final PanelFieldKey<Double> FIELD_CHARGE =
            PanelFieldKey.doubleKey("capacitor:charge");
    public static final PanelFieldKey<Double> FIELD_INTERNAL_RESISTANCE =
            PanelFieldKey.doubleKey("capacitor:internalResistance");

    public Capacitor(World world) {
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

        // Multiply current by negative internal resistance to match the
        // "V_start - V_end - I*R = const" convention used by Resistor/Battery.
        // Stamping +R here inverts the RC feedback sign and makes the solve
        // diverge exponentially instead of converging to Q = C*V.
        equations.stampCoefficient(getCurrent(), -get().getInternalResistance());

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

    @Override
    public void set(CapacitorInfo property) {
        this.info = Objects.requireNonNull(property, "property");
    }

    @Override
    public void set(int index, Object property) {
        if (index < 0 || index > 2) {
            throw new IllegalArgumentException("Unknown property index: " + index);
        }
        if (!(property instanceof Number n)) {
            throw new IllegalArgumentException("Expected Number, got " + property);
        }
        double v = n.doubleValue();
        switch (index) {
            case 0 -> info.setCapacitance(v);
            case 1 -> info.setCharge(v);
            default -> info.setInternalResistance(v);
        }
    }

    protected void handleCapacitance(Actions.SetCapacitanceAction action) {
        info.setCapacitance(action.getValue());
    }

    protected void handleResistance(Actions.SetResistanceAction action) {
        info.setInternalResistance(action.getValue());
    }

    @Override
    public void save(CompoundTag tag) {
        super.save(tag);
        info.save(tag);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.keySet().contains("capacitance")
                && tag.keySet().contains("charge")
                && tag.keySet().contains("internal_resistance")) {
            info.load(tag);
        }
    }

    static {
        AllComponents.CAPACITOR.addActionHandler(ActionTypes.SET_CAPACITANCE, (capacitor, action) -> capacitor.handleCapacitance(action));
        AllComponents.CAPACITOR.addActionHandler(ActionTypes.SET_RESISTANCE, (capacitor, setResistanceAction) -> capacitor.handleResistance(setResistanceAction));
        InfoPanelRegistry.bind(AllComponents.CAPACITOR, PANEL_TYPE);
        InfoPanelRegistry.registerPanel(PANEL_TYPE, (capacitor, builder) -> {
            builder.add(new NumberFieldSpec(FIELD_CAPACITANCE, "Capacitance (F)", capacitor.info.getCapacitance()),
                    (c, ctx) -> ctx.doubleValue(FIELD_CAPACITANCE)
                            .filter(v -> Double.isFinite(v) && v > 0.0)
                            .ifPresent(v -> {
                                c.info.setCapacitance(v);
                                ctx.markChanged(c);
                            }));
            builder.add(new NumberFieldSpec(FIELD_CHARGE, "Charge (C)", capacitor.info.getCharge()),
                    (c, ctx) -> ctx.doubleValue(FIELD_CHARGE)
                            .filter(Double::isFinite)
                            .ifPresent(v -> {
                                c.info.setCharge(v);
                                ctx.markChanged(c);
                            }));
            builder.add(new NumberFieldSpec(FIELD_INTERNAL_RESISTANCE, "Internal Resistance (Ω)",
                            capacitor.info.getInternalResistance()),
                    (c, ctx) -> ctx.doubleValue(FIELD_INTERNAL_RESISTANCE)
                            .filter(v -> Double.isFinite(v) && v > 0.0)
                            .ifPresent(v -> {
                                c.info.setInternalResistance(v);
                                ctx.markChanged(c);
                            }));
        });
    }
}
