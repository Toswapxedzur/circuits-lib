package com.minecart.display.render.registry;

/** Built-in render part keys. */
public final class RenderPartKeys {

    private RenderPartKeys() {}

    public static final RenderPartKey NODE_SPRITE = new RenderPartKey("core:node.sprite");
    public static final RenderPartKey NODE_CURRENT_FILL = new RenderPartKey("core:node.current_fill");
    public static final RenderPartKey EDGE_BRIDGE = new RenderPartKey("core:edge.bridge");
    public static final RenderPartKey EDGE_BODY = new RenderPartKey("core:edge.body");
    public static final RenderPartKey EDGE_CURRENT_FLOW = new RenderPartKey("core:edge.current_flow");
    public static final RenderPartKey COMPONENT_SPRITE = new RenderPartKey("core:component.sprite");
}
