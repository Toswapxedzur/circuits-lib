package com.minecart.snap;

import com.minecart.logic.CircuitNode;
import com.minecart.logic.ServerWorld;
import com.minecart.registry.AllComponents;

import java.util.HashMap;
import java.util.Map;

/**
 * Resolves board {@link Post}s to the shared {@link CircuitNode}s used while deriving a snap board's
 * electrical graph, with union-find so that ideal connectors collapse posts onto a single node.
 *
 * <h2>Why union-find</h2>
 * A snap wire is not a device with resistance — it means "these two posts are the same electrical point".
 * Rather than inject a near-zero resistor (which pollutes the matrix), a connector part calls
 * {@link #union(Post, Post)} and both posts thereafter {@link #at(Post) resolve} to one node. Devices
 * (resistor, battery, …) then attach their core element between the representative nodes. Because every
 * post that isn't unified stays its own node, snapping two devices onto the same post still joins them —
 * that path just goes through {@link #at} with no union.
 *
 * <p>Callers must apply all {@link #union unions} before resolving device nodes (see
 * {@link SnapBoard#rebuild}), so a node is only ever created for a fully-merged representative. Scoped to
 * a single rebuild; a fresh grid is used each time so nodes never leak across rebuilds.
 */
public final class PostGrid {

    private final ServerWorld world;
    private final Map<Post, Post> parent = new HashMap<>();
    private final Map<Post, CircuitNode> nodes = new HashMap<>();

    public PostGrid(ServerWorld world) {
        this.world = world;
    }

    /** Merges the two posts' equivalence classes so they share one node. */
    public void union(Post a, Post b) {
        Post ra = find(a);
        Post rb = find(b);
        if (!ra.equals(rb)) {
            parent.put(ra, rb);
        }
    }

    /** The shared node for {@code post}'s class, creating it on first use. */
    public CircuitNode at(Post post) {
        Post root = find(post);
        return nodes.computeIfAbsent(root, p -> world.createNode(AllComponents.CONNECTION));
    }

    /** Representative post of {@code p}'s class (with path compression). */
    private Post find(Post p) {
        Post cur = parent.getOrDefault(p, p);
        if (cur.equals(p)) {
            return p;
        }
        Post root = find(cur);
        parent.put(p, root);
        return root;
    }

    /** Number of distinct nodes materialized so far. */
    public int size() {
        return nodes.size();
    }
}
