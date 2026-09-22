package com.minecart.elements.edge;

import com.minecart.foundation.World;
import com.minecart.logic.CircuitEdge;
import com.minecart.serialization.tag.CompoundTag;
import com.minecart.spice.SpiceContext;
import com.minecart.variant.ElectricalVariate;
import com.minecart.variant.Informations.SolarCellInfo;

import java.util.Objects;

/**
 * A solar (photovoltaic) cell, modelled as the standard single-diode PV cell: a light-driven current source
 * {@code Iph = iscFullSun · irradiance} in parallel with the junction diode and a shunt resistance, then a series
 * resistance to the {@code +} terminal. ngspice solves the implicit I–V, giving the real photovoltaic curve —
 * roughly constant current up to a knee near the open-circuit voltage. {@code irradiance} (0..1) is written each
 * tick from the incident light + shadow at the cell's face (S2), so a part placed above it drops the output.
 *
 * <p>Terminal {@code start} = {@code +}, {@code end} = {@code −}. Follows the {@link Battery}/{@link Diode}
 * source-edge pattern (its own {@link #emitSpice} model, an {@link com.minecart.variant.Informations.SolarCellInfo}
 * data component, registered via {@link com.minecart.registry.AllComponents#SOLAR_CELL}).
 */
public class SolarCell extends CircuitEdge implements ElectricalVariate<SolarCellInfo> {

    protected SolarCellInfo info;

    public SolarCell(World world) {
        super(world);
        this.info = getDefault();
    }

    /**
     * Single-diode PV model between {@code +} (start) and {@code −} (end): a photocurrent source, the junction
     * diode and the shunt sit between the internal junction node {@code A} and the return node {@code K}; the
     * series resistance links {@code +} to {@code A}; the 0 V ammeter carries the terminal current {@code K → −}.
     */
    @Override
    public void emitSpice(SpiceContext ctx) {
        String s = ctx.start(), a = ctx.mid(), k = ctx.mid() + "k", id = ctx.id();
        double iph = info.photoCurrent();
        // Series resistance from the + terminal to the junction node A.
        ctx.line("r" + id + " " + s + " " + a + " " + SpiceContext.num(info.getSeriesResistance()));
        // Photocurrent source (K → A), the junction diode (A → K) and the shunt (A → K), all in parallel.
        ctx.line("i" + id + " " + k + " " + a + " dc " + SpiceContext.num(iph));
        ctx.line("d" + id + " " + a + " " + k + " dmod" + id);
        ctx.line(".model dmod" + id + " d(is=" + SpiceContext.num(info.getSaturationCurrent())
                + " n=" + SpiceContext.num(info.getIdeality()) + ")");
        ctx.line("rsh" + id + " " + a + " " + k + " " + SpiceContext.num(info.getShuntResistance()));
        // Terminal (output) current returns K → end through the 0 V ammeter — this is i(vm) = getCurrent().
        ctx.ammeterFrom(k);
    }

    @Override
    public void save(CompoundTag tag) {
        super.save(tag);
        info.save(tag);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.keySet().contains("iscFullSun")) {
            info.load(tag);
        }
    }

    @Override
    public SolarCellInfo get() {
        return info;
    }

    @Override
    public SolarCellInfo getDefault() {
        // Toy panel: Isc 0.1 A at full sun; n=6 models a small series stack → Voc ≈ 2.8 V (enough to light an LED).
        return new SolarCellInfo(0.1, 1e-9, 6.0, 1.0, 1000.0);
    }

    @Override
    public void set(SolarCellInfo property) {
        this.info = Objects.requireNonNull(property, "property");
    }

    @Override
    public boolean hasProperty(int index) {
        return index >= 0 && index <= 4;
    }

    @Override
    public Object getProperty(int index) {
        if (info == null) {
            return null;
        }
        return switch (index) {
            case 0 -> info.getIscFullSun();
            case 1 -> info.getSaturationCurrent();
            case 2 -> info.getIdeality();
            case 3 -> info.getSeriesResistance();
            case 4 -> info.getShuntResistance();
            default -> null;
        };
    }

    @Override
    public void set(int index, Object property) {
        if (!hasProperty(index)) {
            throw new IllegalArgumentException("Unknown property index: " + index);
        }
        if (!(property instanceof Number n)) {
            throw new IllegalArgumentException("Expected Number, got " + property);
        }
        double v = n.doubleValue();
        switch (index) {
            case 0 -> info.setIscFullSun(v);
            case 1 -> info.setSaturationCurrent(v);
            case 2 -> info.setIdeality(v);
            case 3 -> info.setSeriesResistance(v);
            default -> info.setShuntResistance(v);
        }
    }
}
