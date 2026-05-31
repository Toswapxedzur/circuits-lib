package com.minecart.display.render.registry;

import com.minecart.logic.CircuitComponent;
import com.minecart.logic.CircuitEdge;
import com.minecart.logic.CircuitElement;
import com.minecart.logic.CircuitNode;

/** Built-in roots of the render tree. */
public final class RenderTypes {

    private RenderTypes() {}

    public static final RenderElementType<CircuitElement> ELEMENT =
            new RenderElementType<>("core:element", CircuitElement.class, null);
    public static final RenderElementType<CircuitNode> NODE =
            new RenderElementType<>("core:node", CircuitNode.class, ELEMENT);
    public static final RenderElementType<CircuitEdge> EDGE =
            new RenderElementType<>("core:edge", CircuitEdge.class, ELEMENT);
    public static final RenderElementType<CircuitComponent> COMPONENT =
            new RenderElementType<>("core:component", CircuitComponent.class, ELEMENT);
}
