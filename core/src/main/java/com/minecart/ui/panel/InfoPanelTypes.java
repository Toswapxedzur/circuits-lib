package com.minecart.ui.panel;

import com.minecart.logic.CircuitComponent;
import com.minecart.logic.CircuitEdge;
import com.minecart.logic.CircuitElement;
import com.minecart.logic.CircuitNode;

/**
 * Built-in roots of the panel type tree. Concrete elements can bind to these directly or introduce
 * intermediate family nodes such as "battery" or "transistor" below them.
 */
public final class InfoPanelTypes {

    private InfoPanelTypes() {}

    public static final InfoPanelElementType<CircuitElement> ELEMENT =
            new InfoPanelElementType<>("core:element", CircuitElement.class, null);

    public static final InfoPanelElementType<CircuitNode> NODE =
            new InfoPanelElementType<>("core:node", CircuitNode.class, ELEMENT);

    public static final InfoPanelElementType<CircuitEdge> EDGE =
            new InfoPanelElementType<>("core:edge", CircuitEdge.class, ELEMENT);

    public static final InfoPanelElementType<CircuitComponent> COMPONENT =
            new InfoPanelElementType<>("core:component", CircuitComponent.class, ELEMENT);
}
