package com.minecart.event.info;

import com.minecart.event.events.ElementInfoInjectEvent;
import com.minecart.foundation.Level;
import com.minecart.logic.CircuitComponent;
import com.minecart.logic.CircuitEdge;
import com.minecart.registry.AllElementInfos;
import com.minecart.variant.info.PositionInfo;
import com.minecart.variant.info.RigidityInfo;
import com.minecart.variant.info.RotationInfo;

/**
 * Default {@link com.minecart.variant.ElementInfo} injectors shipped with {@code :core}: every
 * {@link com.minecart.logic.CircuitElement} gets a {@link PositionInfo} (defaults to {@code (0,0)}), and every
 * {@link CircuitComponent} additionally gets a {@link RotationInfo} (defaults to angle {@code 0}).
 *
 * <p>Both server and client must {@link #attach(Level)} during their level setup so the layout primitives are
 * present consistently. The handlers are no-ops if a prior listener (e.g. a saved world being restored) already
 * injected the info, so saved layouts win.
 */
public final class InfoInjectors {

    private InfoInjectors() {}

    /**
     * Registers position and rotation injectors on {@code level}'s event bus. Idempotent in effect because each
     * handler checks {@link ElementInfoInjectEvent#isPresent} before injecting.
     */
    public static void attach(Level level) {
        level.register(ElementInfoInjectEvent.class, event -> {
            if (event.isPresent(AllElementInfos.POSITION)) {
                return;
            }
            event.inject(AllElementInfos.POSITION, new PositionInfo());
        });
        level.register(ElementInfoInjectEvent.class, event -> {
            if (!(event.getElement() instanceof CircuitComponent)) {
                return;
            }
            if (event.isPresent(AllElementInfos.ROTATION)) {
                return;
            }
            event.inject(AllElementInfos.ROTATION, new RotationInfo());
        });
        // RigidityInfo on every edge: defaults to flexible. Attaching it eagerly means the panel
        // and the physics adapter never have to deal with "absent vs. flexible" — they always see
        // an explicit RigidityInfo and read its boolean. Newly-placed edges get the flexible
        // default; users can opt individual edges into rigid coupling via the edge panel.
        level.register(ElementInfoInjectEvent.class, event -> {
            if (!(event.getElement() instanceof CircuitEdge)) {
                return;
            }
            if (event.isPresent(AllElementInfos.RIGIDITY)) {
                return;
            }
            event.inject(AllElementInfos.RIGIDITY, new RigidityInfo());
        });
    }
}
