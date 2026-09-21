package com.minecart.display.render.engine.behaviour;

import java.util.HashMap;
import java.util.Map;

/**
 * The registry binding a snap component (by model id) to its {@link InteractiveBehaviour} — the single source
 * for "what does dragging this part do", replacing the datagen {@code Interaction} descriptor. A part has at
 * most one interactive control; the control's {@link InteractiveBehaviour#channel()} identifies which movable
 * sub-part it drives.
 */
public final class InteractiveBehaviours {
    private InteractiveBehaviours() {}

    private static final Map<String, InteractiveBehaviour> BY_ID = new HashMap<>();

    static {
        BY_ID.put("switch", new SlideSwitchControl());
        BY_ID.put("press", new MomentaryButtonControl());
        BY_ID.put("varres_bar", new VarResControl("slide", 0f, 2f, false));  // linear slider
        BY_ID.put("varres_clock", new VarResControl("spin", 0f, 1f, true));  // rotary dial
    }

    /** The interactive control for a model id, or {@code null} if the part has none. */
    public static InteractiveBehaviour of(String modelId) {
        return BY_ID.get(modelId);
    }
}
