package com.minecart.elements.edge;

import com.minecart.action.ActionTypes;
import com.minecart.action.Actions;
import com.minecart.foundation.World;
import com.minecart.logic.CircuitEdge;
import com.minecart.math.LinearSystem.RelationProvider;
import com.minecart.misc.CoreStrings;
import com.minecart.registry.AllComponents;
import com.minecart.serialization.tag.CompoundTag;
import com.minecart.tick_history.VariableHistory;
import com.minecart.variant.ElectricalVariate;
import com.minecart.variant.type.Informations;
import com.minecart.variant.type.Informations.DiodeInfo;
/**
 * One-way conductor modeled as a resistor whose effective value switches each tick: forward flow (start→end,
 * nonnegative branch current) uses {@link DiodeInfo#getForwardResistance()}, reverse flow uses
 * {@link DiodeInfo#getReverseResistance()}. Solved branch current is appended to {@link #getCurrentHistory()} each
 * tick after the solve (see {@link VariableHistory}).
 */
public class Diode extends CircuitEdge implements ElectricalVariate<DiodeInfo> {

    /** Default depth for {@link #currentHistory}. */
    public static final int DEFAULT_HISTORY_TICKS = 100;

    protected DiodeInfo info;

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

    protected void handleForwardResistance(Actions.SetResistanceAction action) {
        info.setForwardResistance(action.getValue());
    }

    protected void handleReverseResistance(Actions.SetReverseResistanceAction action) {
        info.setReverseResistance(action.getValue());
    }

    static {
        AllComponents.DIODE.addActionHandler(ActionTypes.SET_RESISTANCE, (diode, action) -> diode.handleForwardResistance(action));
        AllComponents.DIODE.addActionHandler(ActionTypes.SET_REVERSE_RESISTANCE, (diode, action) -> diode.handleReverseResistance(action));
    }
}
