package com.minecart.display.render.engine;

import com.badlogic.gdx.graphics.Color;
import com.minecart.display.render.engine.behaviour.BehaviourContext;
import com.minecart.display.render.engine.behaviour.ComponentBehaviours;
import com.minecart.display.render.engine.behaviour.ReactiveBehaviour;

import java.util.List;
import java.util.Map;

import com.minecart.display.render.engine.PhysicalBoardView.Placed;

/**
 * The reactive-animation driver for the physical board — each frame it feeds every placed part's freshly-solved
 * electrical state (current, voltage, capacitor charge) to its composed {@link ReactiveBehaviour}s and eases the
 * resulting render channels. Split out of {@link PhysicalBoardView} so the board keeps only placement + presentation
 * (SRP); the animation SEMANTICS live on each part's behaviours in {@link ComponentBehaviours} — this class only
 * supplies the {@link BehaviourContext} and advances the easing. Replaces the old per-kind
 * {@code updateMotors}/{@code updateElectricalGlow} switches.
 *
 * <p>Shares the board's {@code placed}/{@code ents} lists and {@code deviceEdge} map by reference (all owned by the
 * board and kept in sync there); this class only reads them.
 */
final class BoardReactive {
    private final List<Placed> placed;
    private final List<EngineRenderer.DynamicEntity> ents;
    private final Map<Integer, com.minecart.logic.CircuitEdge> deviceEdge;

    BoardReactive(List<Placed> placed, List<EngineRenderer.DynamicEntity> ents,
                  Map<Integer, com.minecart.logic.CircuitEdge> deviceEdge) {
        this.placed = placed;
        this.ents = ents;
        this.deviceEdge = deviceEdge;
    }

    /**
     * The single reactive-animation pass: for each placed part, run its composed {@link ReactiveBehaviour}s
     * (motor spin, glow, …) against its freshly-solved electrical state, then ease the channels.
     */
    void update(float dt) {
        for (int i = 0; i < ents.size(); i++) {
            List<ReactiveBehaviour> behs = ComponentBehaviours.reactive(placed.get(i).modelId());
            if (behs.isEmpty()) continue; // conductors / IC — nothing reactive (emission stays cleared)
            EngineRenderer.DynamicEntity e = ents.get(i);
            com.minecart.logic.CircuitEdge edge = deviceEdge.get(i);
            float cur = edge == null ? 0f : (float) Math.abs(edge.getCurrent().getValue());
            float volt = (edge != null && edge.getStart() != null && edge.getEnd() != null)
                    ? (float) Math.abs(edge.getStart().getVoltage().getValue() - edge.getEnd().getVoltage().getValue())
                    : 0f;
            float charge = edge instanceof com.minecart.elements.edge.Capacitor cap
                    ? (float) cap.get().getCharge() : 0f;
            BehaviourContext ctx = new BehaviourContext(dt, cur, volt, charge, channelsOf(e), emissionOf(e));
            for (ReactiveBehaviour b : behs) b.react(ctx);
            e.anim.update(dt); // ease any targeted (LEVEL) channels; a no-op for immediate set() channels
        }
    }

    private BehaviourContext.Channels channelsOf(EngineRenderer.DynamicEntity e) {
        return new BehaviourContext.Channels() {
            @Override public float value(String c) { return e.anim.value(c); }
            @Override public void set(String c, float v) { e.anim.set(c, v); }
            @Override public void target(String c, float v) { e.anim.target(c, v); }
        };
    }

    private BehaviourContext.Emission emissionOf(EngineRenderer.DynamicEntity e) {
        return new BehaviourContext.Emission() {
            @Override public void emit(float r, float g, float b, float range) {
                e.light = new Color(r, g, b, 1f);
                e.lightRange = range;
            }
            @Override public void clear() { e.light = null; e.lightRange = 0f; }
        };
    }

    /** TEST: motor {@code i}'s current spin channel (0..1 = one turn), or NaN if it's not a motor. */
    float debugSpin(int i) {
        return PhysicalBoardView.kind(placed.get(i).modelId()) == 'm' ? ents.get(i).anim.value("spin") : Float.NaN;
    }

    /** TEST: capacitor {@code i}'s swell channel (0 = uncharged/identity, grows with charge), or NaN if not a cap. */
    float debugSwell(int i) {
        return PhysicalBoardView.kind(placed.get(i).modelId()) == 'c' ? ents.get(i).anim.value("swell") : Float.NaN;
    }
}
