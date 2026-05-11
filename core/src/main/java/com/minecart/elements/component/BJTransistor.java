package com.minecart.elements.component;

import com.minecart.elements.edge.Resistor;
import com.minecart.elements.edge.Wire;
import com.minecart.foundation.Circuit;
import com.minecart.logic.CircuitComponent;
import com.minecart.logic.CircuitEdge;
import com.minecart.logic.CircuitNode;
import com.minecart.math.LinearSystem;
import com.minecart.misc.CoreStrings;
import com.minecart.registry.AllComponents;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;
import com.minecart.variant.ElectricalVariate;
import com.minecart.variant.Informations.BJTInfo;

import java.util.Objects;

/**
 * Bipolar junction transistor as a Y-shaped internal graph: {@code center} connects to {@code base} (ideal
 * {@link AllComponents#PERFECT_WIRE}), {@code collector} ({@link AllComponents#CIRCUIT_EDGE}), and {@code emitter}
 * ({@link AllComponents#WIRE}). Ports: {@link #getPort(int)} 0 = base, 1 = collector, 2 = emitter.
 * <p>
 * Adds one constitutive relation {@code I_collector = β I_B} between the center–collector and center–base branch
 * currents. Call {@link #generate()} after {@link Circuit#addComponent(CircuitComponent)} when creating a new
 * instance (not needed after {@link #load(CompoundTag)} from a saved circuit).
 */
public class BJTransistor extends CircuitComponent implements ElectricalVariate<BJTInfo> {

    protected BJTInfo info;

    protected CircuitNode center;
    protected CircuitNode base;
    protected CircuitNode collector;
    protected CircuitNode emitter;

    protected Wire edgeBase;
    protected CircuitEdge edgeCollector;
    protected Resistor edgeEmitter;

    public BJTransistor() {
        super();
        this.info = new BJTInfo(100.0);
    }

    public BJTInfo getInfo() {
        return info;
    }

    @Override
    public BJTInfo get() {
        return info;
    }

    @Override
    public BJTInfo getDefault() {
        return new BJTInfo(100.0);
    }

    @Override
    public boolean hasProperty(int index) {
        return index == 0;
    }

    @Override
    public Object getProperty(int index) {
        return index == 0 ? info.getBeta() : null;
    }

    @Override
    public void set(BJTInfo property) {
        this.info = Objects.requireNonNull(property, "property");
    }

    @Override
    public void set(int index, Object property) {
        if (index != 0) {
            throw new IllegalArgumentException("Unknown property index: " + index);
        }
        if (!(property instanceof Number n)) {
            throw new IllegalArgumentException("Expected Number for beta, got " + property);
        }
        info.setBeta(n.doubleValue());
    }

    /**
     * Builds internal nodes and edges. Idempotent; skips if already generated or restored from tags.
     */
    @Override
    public void generate() {
        if (center != null) {
            return;
        }
        center = newNode(AllComponents.CONNECTION);
        base = newNode(AllComponents.CONNECTION);
        collector = newNode(AllComponents.CONNECTION);
        emitter = newNode(AllComponents.CONNECTION);
        edgeBase = newEdge(AllComponents.WIRE, center, base);
        edgeCollector = newEdge(AllComponents.CIRCUIT_EDGE, center, collector);
        edgeEmitter = newEdge(AllComponents.RESISTOR, center, emitter);
    }

    @Override
    public void collectRule(LinearSystem.RelationProvider equations) {
        if (edgeCollector == null || edgeBase == null || info == null) {
            return;
        }
        equations.stampCoefficient(edgeCollector.getCurrent(), 1.0);
        equations.stampCoefficient(edgeBase.getCurrent(), -info.getBeta());
        equations.stampConstant(0.0);
        equations.endRelation();
    }

    @Override
    public CircuitNode getPort(int index) {
        return switch (index) {
            case 0 -> base;
            case 1 -> collector;
            case 2 -> emitter;
            default -> null;
        };
    }

    @Override
    public void save(CompoundTag tag) {
        super.save(tag);
        CompoundTag sub = new CompoundTag();
        info.save(sub);
        if (center != null) {
            TagUtil.putUUID(sub, "center", center.getId());
        }
        if (base != null) {
            TagUtil.putUUID(sub, "base", base.getId());
        }
        if (collector != null) {
            TagUtil.putUUID(sub, "collector", collector.getId());
        }
        if (emitter != null) {
            TagUtil.putUUID(sub, "emitter", emitter.getId());
        }
        if (edgeBase != null) {
            TagUtil.putUUID(sub, "edge_base", edgeBase.getId());
        }
        if (edgeCollector != null) {
            TagUtil.putUUID(sub, "edge_collector", edgeCollector.getId());
        }
        if (edgeEmitter != null) {
            TagUtil.putUUID(sub, "edge_emitter", edgeEmitter.getId());
        }
        tag.put(CoreStrings.COMPONENT_BJT_INFO, sub);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.get(CoreStrings.COMPONENT_BJT_INFO) instanceof CompoundTag sub) {
            info.load(sub);
            Circuit c = getCircuit();
            if (c == null) {
                throw new IllegalStateException("BJTransistor has no circuit");
            }
            center = c.findNode(TagUtil.getUUID(sub, "center"));
            base = c.findNode(TagUtil.getUUID(sub, "base"));
            collector = c.findNode(TagUtil.getUUID(sub, "collector"));
            emitter = c.findNode(TagUtil.getUUID(sub, "emitter"));
            edgeBase = (Wire) c.findEdge(TagUtil.getUUID(sub, "edge_base"));
            edgeCollector = c.findEdge(TagUtil.getUUID(sub, "edge_collector"));
            edgeEmitter = (Resistor) c.findEdge(TagUtil.getUUID(sub, "edge_emitter"));
        }
    }
}
