package com.minecart.elements.edge;

import com.minecart.action.ActionTypes;
import com.minecart.action.Actions;
import com.minecart.foundation.World;
import com.minecart.logic.CircuitEdge;
import com.minecart.math.LinearSystem.RelationProvider;
import com.minecart.misc.CoreStrings;
import com.minecart.registry.AllComponents;
import com.minecart.serialization.tag.CompoundTag;
import com.minecart.ui.panel.InfoPanelElementType;
import com.minecart.ui.panel.InfoPanelRegistry;
import com.minecart.ui.panel.InfoPanelTypes;
import com.minecart.ui.panel.PanelFieldKey;
import com.minecart.ui.panel.fields.LabelSpec;
import com.minecart.ui.panel.fields.NumberFieldSpec;
import com.minecart.variant.ElectricalVariate;
import com.minecart.variant.Informations;
import com.minecart.variant.Informations.DiodeInfo;

import java.util.Objects;

/**
 * One-way conductor modeled as a resistor whose effective value switches each tick: forward flow (start→end,
 * nonnegative branch current) uses {@link DiodeInfo#getForwardResistance()}, reverse flow uses
 * {@link DiodeInfo#getReverseResistance()}.
 */
public class Diode extends CircuitEdge implements ElectricalVariate<DiodeInfo> {

    protected DiodeInfo info;

    public static final InfoPanelElementType<Diode> PANEL_TYPE =
            new InfoPanelElementType<>("diode", Diode.class, InfoPanelTypes.EDGE);
    public static final PanelFieldKey<Double> FIELD_FORWARD_RESISTANCE =
            PanelFieldKey.doubleKey("diode:forwardResistance");
    public static final PanelFieldKey<Double> FIELD_REVERSE_RESISTANCE =
            PanelFieldKey.doubleKey("diode:reverseResistance");
    public static final PanelFieldKey<String> FIELD_EFFECTIVE_RESISTANCE =
            PanelFieldKey.stringKey("diode:effectiveResistance");

    public Diode(World world) {
        super(world);
        this.info = getDefault();
    }

    @Override
    public void collectRule(RelationProvider equations) {
        super.collectRule(equations);

        if (!isConnected() || info == null) {
            return;
        }

        equations.stampCoefficient(getStart().getVoltage(), 1.0);
        equations.stampCoefficient(getEnd().getVoltage(), -1.0);
        equations.stampCoefficient(getCurrent(), -info.getEffectiveResistance());
        equations.stampConstant(0.0);
        equations.endRelation();
    }

    @Override
    public void tick() {
        super.tick();

        if (!isConnected() || info == null) {
            return;
        }

        double i = getCurrent().getValue();
        if (i < 0.0) {
            info.setEffectiveResistance(info.getReverseResistance());
        } else {
            info.setEffectiveResistance(info.getForwardResistance());
        }
    }

    @Override
    public void save(CompoundTag tag) {
        super.save(tag);
        CompoundTag sub = new CompoundTag();
        info.save(sub);
        tag.put(CoreStrings.EDGE_DIODE_INFO, sub);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.get(CoreStrings.EDGE_DIODE_INFO) instanceof CompoundTag sub) {
            info.load(sub);
        }
    }

    @Override
    public DiodeInfo get() {
        return info;
    }

    @Override
    public DiodeInfo getDefault() {
        return new DiodeInfo(1.0, Informations.LARGE);
    }

    @Override
    public boolean hasProperty(int index) {
        return index >= 0 && index <= 1;
    }

    @Override
    public Object getProperty(int index) {
        if (info == null) {
            return null;
        }
        return switch (index) {
            case 0 -> info.getForwardResistance();
            case 1 -> info.getReverseResistance();
            default -> null;
        };
    }

    @Override
    public void set(DiodeInfo property) {
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
            info.setForwardResistance(n.doubleValue());
        } else {
            info.setReverseResistance(n.doubleValue());
        }
    }

    protected void handleForwardResistance(Actions.SetResistanceAction action) {
        info.setForwardResistance(action.getValue());
    }

    protected void handleReverseResistance(Actions.SetReverseResistanceAction action) {
        info.setReverseResistance(action.getValue());
    }

    static {
        AllComponents.DIODE.addActionHandler(ActionTypes.SET_RESISTANCE, (diode, action) -> diode.handleForwardResistance(action));
        AllComponents.DIODE.addActionHandler(ActionTypes.SET_REVERSE_RESISTANCE, (diode, action) -> diode.handleReverseResistance(action));
        InfoPanelRegistry.bind(AllComponents.DIODE, PANEL_TYPE);
        InfoPanelRegistry.registerPanel(PANEL_TYPE, (diode, builder) -> {
            builder.add(new NumberFieldSpec(FIELD_FORWARD_RESISTANCE, "Forward Resistance (Ω)",
                            diode.info.getForwardResistance()),
                    (d, ctx) -> ctx.doubleValue(FIELD_FORWARD_RESISTANCE)
                            .filter(v -> Double.isFinite(v) && v > 0.0)
                            .ifPresent(v -> {
                                d.info.setForwardResistance(v);
                                ctx.markChanged(d);
                            }));
            builder.add(new NumberFieldSpec(FIELD_REVERSE_RESISTANCE, "Reverse Resistance (Ω)",
                            diode.info.getReverseResistance()),
                    (d, ctx) -> ctx.doubleValue(FIELD_REVERSE_RESISTANCE)
                            .filter(v -> Double.isFinite(v) && v > 0.0)
                            .ifPresent(v -> {
                                d.info.setReverseResistance(v);
                                ctx.markChanged(d);
                            }));
            builder.add(new LabelSpec(FIELD_EFFECTIVE_RESISTANCE, "Effective Resistance (Ω)",
                    Double.toString(diode.info.getEffectiveResistance())));
        });
    }
}
