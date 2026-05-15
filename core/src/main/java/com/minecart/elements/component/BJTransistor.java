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
     * {@code base}, {@code collector}, and {@code emitter} are registered as public ports 0/1/2 via
     * the {@code (type, portIndex)} overload so external wires can attach to them; {@code center} is
     * an auxiliary internal junction (only the constitutive equation in {@link #collectRule} touches
     * it) and goes through the un-indexed {@code newNode} so it stays hidden + unwireable.
     */
    @Override
    public void generate() {
        if (center != null) {
            return;
        }
        center = newNode(AllComponents.CONNECTION);
        base = newNode(AllComponents.CONNECTION, 0);
        collector = newNode(AllComponents.CONNECTION, 1);
        emitter = newNode(AllComponents.CONNECTION, 2);
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

    // getPort(int) is inherited from CircuitComponent and reads portsByIndex, populated above.

    public CircuitNode getCenter() {
        return center;
    }

    @Override
    public void save(CompoundTag tag) {
        super.save(tag);
        // Port nodes (base / collector / emitter) are persisted by the base class via the port_bindings
        // tag, so they don't need their own per-name UUID slots here. Only auxiliary internals (the
        // hidden junction `center`) and the typed internal edges still need to be saved by name so
        // {@link #load} can rebind the typed handles {@link #collectRule} reads.
        CompoundTag sub = new CompoundTag();
        info.save(sub);
        if (center != null) {
            TagUtil.putUUID(sub, "center", center.getId());
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
        // Rebind the typed port handles from the now-populated port map. {@link #collectRule} doesn't
        // touch them today (it uses the typed edges), but downstream code that reaches into BJT for
        // base/collector/emitter directly still expects them populated post-load.
        base = getPort(0);
        collector = getPort(1);
        emitter = getPort(2);
        if (tag.get(CoreStrings.COMPONENT_BJT_INFO) instanceof CompoundTag sub) {
            info.load(sub);
            Circuit c = getCircuit();
            if (c == null) {
                throw new IllegalStateException("BJTransistor has no circuit");
            }
            // Internal nodes/edges may have been merged into a different circuit than this component
            // during {@code generate()}, so resolve through the world rather than this circuit alone.
            // (Mirrors the world-scoped lookup the base class added to {@link CircuitComponent#load}.)
            center = findInWorld(c, TagUtil.getUUID(sub, "center"));
            edgeBase = (Wire) findEdgeInWorld(c, TagUtil.getUUID(sub, "edge_base"));
            edgeCollector = findEdgeInWorld(c, TagUtil.getUUID(sub, "edge_collector"));
            edgeEmitter = (Resistor) findEdgeInWorld(c, TagUtil.getUUID(sub, "edge_emitter"));
            // Backward compatibility: legacy saves wrote "base"/"collector"/"emitter" UUIDs in this
            // sub-tag instead of the new port_bindings list. If the new map didn't restore the typed
            // handles (port map empty for old files), fall back to the legacy keys.
            if (base == null) base = findInWorld(c, TagUtil.getUUID(sub, "base"));
            if (collector == null) collector = findInWorld(c, TagUtil.getUUID(sub, "collector"));
            if (emitter == null) emitter = findInWorld(c, TagUtil.getUUID(sub, "emitter"));
        }
    }

    private static CircuitNode findInWorld(Circuit c, java.util.UUID id) {
        if (id == null) return null;
        return c.getWorld() != null ? c.getWorld().findNode(id) : c.findNode(id);
    }

    private static com.minecart.logic.CircuitEdge findEdgeInWorld(Circuit c, java.util.UUID id) {
        if (id == null) return null;
        return c.getWorld() != null ? c.getWorld().findEdge(id) : c.findEdge(id);
    }
}
