package com.minecart.display.editor;

import com.minecart.registry.CircuitElementType;

import java.util.UUID;

/**
 * Discriminated union of editor tool states. Drives {@link Editor}'s click/drag handling and the palette
 * highlight. New element kinds extend this sealed hierarchy.
 */
public sealed interface EditorTool {

    /** No tool selected; clicks select existing elements (future feature). */
    record Idle() implements EditorTool {}

    /** Place a free node on left-click (or drop). */
    record PlaceNode(CircuitElementType<?> type) implements EditorTool {}

    /** Place a component on left-click (or drop) with the current rotation in radians. */
    record PlaceComponent(CircuitElementType<?> type, double angle) implements EditorTool {
        public PlaceComponent withAngle(double newAngle) {
            return new PlaceComponent(type, newAngle);
        }
    }

    /**
     * Connect-edge in two-click mode. {@code firstNodeId} is null until the user selects the first endpoint;
     * the second click on a node completes the edge.
     */
    record ConnectEdge(CircuitElementType<?> type, UUID firstNodeId) implements EditorTool {
        public ConnectEdge withFirst(UUID id) {
            return new ConnectEdge(type, id);
        }
    }
}
