package com.minecart.registry;

import com.minecart.logic.CircuitComponent;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Per-{@link CircuitElementType} description of where each port of a {@link CircuitComponent} sits relative
 * to the component's centre, used by the renderer (sprite layout, click hit-test) and by the server (to
 * compute world coordinates for each internal port node when a component is placed or rotated).
 *
 * <p>Anchor offsets are in the same world units as {@link com.minecart.variant.info.PositionInfo}. Rotation
 * is applied via {@link #worldPositionOf(Anchor, double, double, double)} using the component's
 * {@link com.minecart.variant.info.RotationInfo} angle (radians).
 *
 * <p>Registration is open until first lookup; in practice anchors are registered alongside their component
 * in a static block in {@link AllComponents}.
 */
public final class ComponentAnchorRegistry {

    private static final Map<CircuitElementType<?>, List<Anchor>> REGISTRY = new HashMap<>();

    private ComponentAnchorRegistry() {}

    /**
     * Registers the anchor list for {@code type}. Replaces any previous registration (later modules can
     * override defaults shipped with {@code :core}).
     */
    public static void register(CircuitElementType<? extends CircuitComponent> type, List<Anchor> anchors) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(anchors, "anchors");
        REGISTRY.put(type, List.copyOf(anchors));
    }

    /**
     * @return immutable anchor list for {@code type}; empty list if none registered (so callers don't need
     *         null checks).
     */
    public static List<Anchor> getAnchors(CircuitElementType<?> type) {
        List<Anchor> list = REGISTRY.get(type);
        return list != null ? list : Collections.emptyList();
    }

    /**
     * Rotates {@code anchor}'s offset by {@code angle} (radians, counter-clockwise) and translates by the
     * component's centre {@code (cx, cy)}. Used by the server to keep each internal port node's
     * {@link com.minecart.variant.info.PositionInfo} consistent with the component's pose, and by the client
     * to figure out where to draw / hit-test each port.
     */
    public static double[] worldPositionOf(Anchor anchor, double cx, double cy, double angle) {
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        double wx = cx + anchor.offsetX() * cos - anchor.offsetY() * sin;
        double wy = cy + anchor.offsetX() * sin + anchor.offsetY() * cos;
        return new double[] { wx, wy };
    }

    /**
     * One port of a component at a fixed offset from its centre.
     *
     * @param portIndex matches {@link CircuitComponent#getPort(int)} on the component instance
     * @param offsetX   world units along the component's local +X axis (before rotation)
     * @param offsetY   world units along the component's local +Y axis (before rotation)
     */
    public record Anchor(int portIndex, double offsetX, double offsetY) {}
}
