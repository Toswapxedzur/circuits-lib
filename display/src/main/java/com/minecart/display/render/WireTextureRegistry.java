package com.minecart.display.render;

import com.minecart.logic.CircuitEdge;
import com.minecart.variant.ElectricalInfo;
import com.minecart.variant.ElectricalVariate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Display-side mapping from a {@link CircuitEdge} to the wire texture id that {@link EdgeActor} tiles
 * along the bridge ("line extension") portion of the segment. Decoupled from each edge's own body
 * texture so a resistor's body sprite and the wires extending out of it can come from different PNGs.
 *
 * <h2>Lookup</h2>
 * Rules are evaluated in registration order; the first matching rule wins. A rule matches when:
 * <ul>
 *   <li>its {@code typeId} is {@code null} (catch-all) or string-equal to {@link CircuitEdge#getRegistryTypeId()}, AND</li>
 *   <li>its {@code matcher} is {@code null} (catch-all) or accepts the {@link ElectricalInfo} extracted from the edge.</li>
 * </ul>
 * For edges that don't implement {@link ElectricalVariate} the matcher is invoked with {@code null}; a
 * non-null matcher must explicitly handle that case if it wants to match such edges. When no rule matches
 * the registry returns {@link #DEFAULT_WIRE_ID} so {@link Textures#getById(String)} falls back to its
 * own placeholder rather than crashing the renderer.
 *
 * <h2>Future variant keys</h2>
 * The {@link Predicate} key on {@link ElectricalInfo} is intentionally permissive: a high-power resistor
 * could later register {@code ("resistor", info -> ((ResistorInfo) info).getResistance() > 1e6, "highr_wire")}
 * before the catch-all without changing this API. For the current iteration only the default catch-all
 * rule is registered at startup, so every edge renders with the same wire texture.
 *
 * <h2>Threading</h2>
 * All access is expected to come from the LibGDX render thread. Rules are usually registered once during
 * display bootstrap and then read every frame from {@link EdgeActor}; no synchronization is provided.
 */
public final class WireTextureRegistry {

    /** Texture id used when no rule matches (loads {@code texture/wire.png} via {@link Textures}). */
    public static final String DEFAULT_WIRE_ID = "wire";

    private static final WireTextureRegistry INSTANCE = new WireTextureRegistry();

    public static WireTextureRegistry get() {
        return INSTANCE;
    }

    private final List<Rule> rules = new ArrayList<>();

    private WireTextureRegistry() {
    }

    /**
     * Adds a rule. Pass {@code null} for {@code typeId} to match every edge type, and {@code null} for
     * {@code matcher} to match every {@link ElectricalInfo} (including the {@code null} bag of edges that
     * don't implement {@link ElectricalVariate}).
     */
    public void register(String typeId, Predicate<ElectricalInfo> matcher, String wireTextureId) {
        rules.add(new Rule(typeId, matcher, Objects.requireNonNull(wireTextureId, "wireTextureId")));
    }

    /** Drops every registered rule. Useful in tests; production code seeds once at startup. */
    public void clear() {
        rules.clear();
    }

    /** Returns the wire texture id for {@code edge}, or {@link #DEFAULT_WIRE_ID} if no rule matched. */
    public String lookup(CircuitEdge edge) {
        if (edge == null) {
            return DEFAULT_WIRE_ID;
        }
        String typeId = edge.getRegistryTypeId();
        ElectricalInfo info = extractInfo(edge);
        for (Rule r : rules) {
            if (r.typeId != null && !r.typeId.equals(typeId)) continue;
            if (r.matcher != null && !r.matcher.test(info)) continue;
            return r.wireTextureId;
        }
        return DEFAULT_WIRE_ID;
    }

    /**
     * Pulls the current {@link ElectricalInfo} bag off an edge, or {@code null} if the edge isn't an
     * {@link ElectricalVariate}. Exposed mainly for callers that want to do their own keying outside the
     * rule walk; the {@link #lookup(CircuitEdge)} path uses it internally too.
     */
    public static ElectricalInfo extractInfo(CircuitEdge edge) {
        if (edge instanceof ElectricalVariate<?> v) {
            return v.get();
        }
        return null;
    }

    private record Rule(String typeId, Predicate<ElectricalInfo> matcher, String wireTextureId) {
    }
}
