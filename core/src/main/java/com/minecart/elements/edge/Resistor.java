package com.minecart.elements.edge;

import com.minecart.action.ActionTypes;
import com.minecart.action.Actions;
import com.minecart.logic.CircuitEdge;
import com.minecart.foundation.World;
import com.minecart.registry.AllComponents;
import com.minecart.serialization.tag.CompoundTag;
import com.minecart.ui.panel.InfoPanelElementType;
import com.minecart.ui.panel.InfoPanelRegistry;
import com.minecart.ui.panel.InfoPanelTypes;
import com.minecart.ui.panel.PanelFieldKey;
import com.minecart.ui.panel.fields.NumberFieldSpec;
import com.minecart.variant.ElectricalVariate;
import com.minecart.variant.Informations.ResistorInfo;
import com.minecart.math.LinearSystem.RelationProvider;

import java.util.Objects;

/**
 * An Ohmic resistor
 */
public class Resistor extends CircuitEdge implements ElectricalVariate<ResistorInfo> {

    protected ResistorInfo info;

    public static final InfoPanelElementType<Resistor> PANEL_TYPE =
            new InfoPanelElementType<>("resistor", Resistor.class, InfoPanelTypes.EDGE);
    public static final PanelFieldKey<Double> FIELD_RESISTANCE =
            PanelFieldKey.doubleKey("resistance");

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
        // Default to a 1.0 Ohm resistor
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

    @Override
    public void set(ResistorInfo property) {
        this.info = Objects.requireNonNull(property, "property");
    }

    @Override
    public void set(int index, Object property) {
        if (index != 0) {
            throw new IllegalArgumentException("Unknown property index: " + index);
        }
        if (!(property instanceof Number n)) {
            throw new IllegalArgumentException("Expected Number for resistance, got " + property);
        }
        info.setResistance(n.doubleValue());
    }

    protected void handleResistance(Actions.SetResistanceAction action) {
        info.setResistance(action.getValue());
        // Replicate the new resistance to every client mirror this tick. Without this, the action
        // mutates server state but the standard delta sync never sees the change (resistance lives
        // in ResistorInfo, not in the generic ElementInfo map saveInfos() walks), so the panel-save
        // path used to leave the client showing stale values until a full reload.
        if (getWorld() != null) {
            getWorld().getLevel().notifyElementChanged(this);
        }
    }

    /**
     * Includes the resistor's {@link ResistorInfo} alongside the inherited edge state so the
     * resistance value flows through both disk persistence ({@link com.minecart.server.persistence.WorldStorage})
     * and the network sync layer ({@link com.minecart.protocol.sync.SyncRegistry}), both of which
     * dispatch through this method via {@link CircuitEdge#save}. Without this override, mutations
     * to {@code info.resistance} (info panel save, {@link Actions.SetResistanceAction}) update only
     * the server in memory and would silently revert on reload / stay invisible to client mirrors.
     */
    @Override
    public void save(CompoundTag tag) {
        super.save(tag);
        info.save(tag);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        // Only let info.load run if the tag actually carries a resistance entry. Without this
        // guard, opening a legacy save (or any tag a sync delta omits the field from)
        // would let ResistorInfo.load read CompoundTag's default-0.0, clamping to DELTA and
        // silently destroying the value. Keys: ResistorInfo serializes under the literal
        // "resistance" — kept in sync with Informations.ResistorInfo.TAG_RESISTANCE.
        if (tag.keySet().contains("resistance")) {
            info.load(tag);
        }
    }

    static {
        AllComponents.RESISTOR.addActionHandler(ActionTypes.SET_RESISTANCE, (resistor, action) -> resistor.handleResistance(action));

        InfoPanelRegistry.bind(AllComponents.RESISTOR, PANEL_TYPE);
        InfoPanelRegistry.registerPanel(PANEL_TYPE, (r, builder) ->
                builder.add(new NumberFieldSpec(FIELD_RESISTANCE, "Resistance (Ω)", r.info.getResistance()),
                        (resistor, ctx) -> ctx.doubleValue(FIELD_RESISTANCE)
                                .filter(v -> Double.isFinite(v) && v > 0.0)
                                .ifPresent(v -> {
                                    resistor.info.setResistance(v);
                                    ctx.markChanged(resistor);
                                })));
    }
}