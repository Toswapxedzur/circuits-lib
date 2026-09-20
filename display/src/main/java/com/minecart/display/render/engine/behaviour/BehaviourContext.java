package com.minecart.display.render.engine.behaviour;

/**
 * The single seam a {@link PartBehaviour} reads and writes through, so behaviours stay decoupled from the
 * engine internals (the {@code DynamicEntity} / {@code AnimationState} that actually hold the state live in
 * the parent {@code render.engine} package). The board view builds one of these per part per frame and
 * implements {@link Channels}/{@link Emission} by delegating to that part's entity.
 */
public final class BehaviourContext {
    private final float dt;
    private final float current;
    private final float voltage;
    private final float charge;
    private final Channels channels;
    private final Emission emission;

    public BehaviourContext(float dt, float current, float voltage, float charge,
                            Channels channels, Emission emission) {
        this.dt = dt;
        this.current = current;
        this.voltage = voltage;
        this.charge = charge;
        this.channels = channels;
        this.emission = emission;
    }

    /** Seconds since the last frame. */
    public float dt() { return dt; }
    /** |I| through this part's device edge (amps), or 0 if it has none. */
    public float current() { return current; }
    /** |V| across this part's device edge (volts), or 0. */
    public float voltage() { return voltage; }
    /** Stored charge on this part if it is a capacitor (coulombs), or 0. */
    public float charge() { return charge; }

    /** Current value of an animation channel. */
    public float channel(String name) { return channels.value(name); }
    /** Set a channel immediately (no easing). */
    public void setChannel(String name, float value) { channels.set(name, value); }
    /** Ease a channel toward {@code value} (applied by the per-frame anim update). */
    public void targetChannel(String name, float value) { channels.target(name, value); }
    /** Emit point light of this colour and range from the part. */
    public void emit(float r, float g, float b, float range) { emission.emit(r, g, b, range); }
    /** Stop emitting. */
    public void clearEmission() { emission.clear(); }

    /** Animation-channel access; the board view backs this with the part's {@code AnimationState}. */
    public interface Channels {
        float value(String name);
        void set(String name, float value);
        void target(String name, float value);
    }

    /** Point-light emission sink; the board view backs this with the part's entity light/range. */
    public interface Emission {
        void emit(float r, float g, float b, float range);
        void clear();
    }
}
