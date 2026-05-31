package com.minecart.display.render.bounds;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.minecart.logic.CircuitComponent;
import com.minecart.logic.CircuitEdge;
import com.minecart.logic.CircuitElement;
import com.minecart.logic.CircuitNode;
import com.minecart.registry.CircuitElementType;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Flat hit-box registry keyed by element type id. It is deliberately separate from the renderer tree:
 * visual inheritance should not force interaction bounds to inherit the same way.
 */
public final class BoundingBoxRegistry {

    private static final Map<String, BoundingBoxProvider<?>> PROVIDERS = new HashMap<>();
    private static boolean defaultsInstalled;

    private BoundingBoxRegistry() {}

    public static <T extends CircuitElement> void register(
            CircuitElementType<T> type, BoundingBoxProvider<? super T> provider) {
        Objects.requireNonNull(type, "type");
        register(type.getTypeId(), provider);
    }

    public static void register(String typeId, BoundingBoxProvider<?> provider) {
        PROVIDERS.put(Objects.requireNonNull(typeId, "typeId"),
                Objects.requireNonNull(provider, "provider"));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static boolean contains(CircuitElement element, Actor actor, float worldX, float worldY) {
        BoundingBoxProvider provider = PROVIDERS.get(element.getRegistryTypeId());
        if (provider == null) {
            provider = fallbackFor(element);
        }
        return provider != null && provider.contains(element, actor, worldX, worldY);
    }

    public static void installDefaults() {
        if (defaultsInstalled) {
            return;
        }
        defaultsInstalled = true;
        DefaultBoundingBoxes.install();
    }

    private static BoundingBoxProvider<?> fallbackFor(CircuitElement element) {
        if (element instanceof CircuitNode) {
            return DefaultBoundingBoxes.NODE;
        }
        if (element instanceof CircuitComponent) {
            return DefaultBoundingBoxes.COMPONENT;
        }
        if (element instanceof CircuitEdge) {
            return DefaultBoundingBoxes.EDGE;
        }
        return null;
    }
}
