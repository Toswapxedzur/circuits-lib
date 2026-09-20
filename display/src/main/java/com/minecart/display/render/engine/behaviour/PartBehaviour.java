package com.minecart.display.render.engine.behaviour;

/**
 * Base marker for a snap-part behaviour — a single-responsibility, stateless unit of animation or
 * interaction attached to a component (the Minecraft-Create {@code BlockEntityBehaviour} analog).
 *
 * <p>Behaviours are <b>stateless and shared</b> across all placed parts of their type: per-placement state
 * lives in the part's {@code AnimationState} channels and its emission, reached only through
 * {@link BehaviourContext}. This keeps behaviour out of the central {@code switch(kind)} blocks — a part's
 * animation is the sum of its composed behaviours ({@link ComponentBehaviours}), so adding an animated part
 * is one registry line plus (if new) one behaviour class, editing zero switches.
 */
public interface PartBehaviour {
}
