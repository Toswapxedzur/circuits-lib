package com.minecart.display.render.registry;

import com.minecart.display.render.registry.parts.ComponentSpritePart;
import com.minecart.display.render.registry.parts.EdgeBodyPart;
import com.minecart.display.render.registry.parts.EdgeBridgePart;
import com.minecart.display.render.registry.parts.EdgeCurrentFlowPart;
import com.minecart.display.render.registry.parts.NodeCurrentFillPart;
import com.minecart.display.render.registry.parts.NodeSpritePart;

/** Registers the built-in code renderers. Idempotent enough for app startup usage. */
public final class DefaultRenderRegistrations {

    private static boolean installed;

    private DefaultRenderRegistrations() {}

    public static void install() {
        if (installed) {
            return;
        }
        installed = true;
        RenderRegistry.register(RenderTypes.NODE,
                builder -> builder
                        .add(RenderPartKeys.NODE_SPRITE, new NodeSpritePart())
                        .add(RenderPartKeys.NODE_CURRENT_FILL, new NodeCurrentFillPart()));
        RenderRegistry.register(RenderTypes.EDGE,
                builder -> builder
                        .add(RenderPartKeys.EDGE_BRIDGE, new EdgeBridgePart())
                        .add(RenderPartKeys.EDGE_BODY, new EdgeBodyPart())
                        .add(RenderPartKeys.EDGE_CURRENT_FLOW, new EdgeCurrentFlowPart()));
        RenderRegistry.register(RenderTypes.COMPONENT,
                builder -> builder.add(RenderPartKeys.COMPONENT_SPRITE, new ComponentSpritePart()));
    }
}
