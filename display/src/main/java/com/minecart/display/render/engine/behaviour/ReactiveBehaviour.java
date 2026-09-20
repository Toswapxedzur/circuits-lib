package com.minecart.display.render.engine.behaviour;

/**
 * A behaviour driven by the solved electrical state each frame — current / voltage / charge → motion or
 * emission (the Create "kinetic speed → rotation" analog). Stateless; reads the solved state and writes the
 * animation channels / emission through {@link BehaviourContext}.
 */
public interface ReactiveBehaviour extends PartBehaviour {
    /** Called once per frame with the part's freshly-solved electrical state. */
    void react(BehaviourContext ctx);
}
