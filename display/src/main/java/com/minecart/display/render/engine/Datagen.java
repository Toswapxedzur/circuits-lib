package com.minecart.display.render.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The datagen dependency resolver. Assets (textures + models + part-types) form a graph: a model borrows the
 * textures its faces reference and the part-types its movables use, so those must be <b>generated first</b>.
 * {@link #order} topologically sorts every asset so each comes after all it depends on; a <b>cross / cyclic</b>
 * dependency (or a dangling one) throws — datagen refuses to run rather than emit a broken set. Node ids are
 * namespaced ({@code tex:…}, {@code model:…}) so the three kinds share one graph.
 */
final class Datagen {

    private Datagen() {}

    /**
     * Topologically orders the nodes so every id appears AFTER all of its dependencies.
     *
     * @param deps id → the ids it depends on (must themselves be keys)
     * @throws IllegalStateException on a missing dependency or a dependency cycle
     */
    static List<String> order(Map<String, ? extends Collection<String>> deps) {
        Map<String, Integer> indeg = new LinkedHashMap<>();
        Map<String, List<String>> dependents = new LinkedHashMap<>();
        for (String n : deps.keySet()) {
            indeg.putIfAbsent(n, 0);
            dependents.putIfAbsent(n, new ArrayList<>());
        }
        for (Map.Entry<String, ? extends Collection<String>> e : deps.entrySet()) {
            for (String d : e.getValue()) {
                if (!deps.containsKey(d)) {
                    throw new IllegalStateException("Missing dependency '" + d + "' required by '" + e.getKey()
                            + "' — generate it first.");
                }
                dependents.get(d).add(e.getKey());
                indeg.merge(e.getKey(), 1, Integer::sum);
            }
        }
        Deque<String> ready = new ArrayDeque<>();
        for (Map.Entry<String, Integer> e : indeg.entrySet()) if (e.getValue() == 0) ready.add(e.getKey());
        List<String> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            String n = ready.poll();
            ordered.add(n);
            for (String m : dependents.get(n)) if (indeg.merge(m, -1, Integer::sum) == 0) ready.add(m);
        }
        if (ordered.size() != deps.size()) {
            List<String> inCycle = new ArrayList<>(deps.keySet());
            inCycle.removeAll(ordered);
            throw new IllegalStateException("Cross/cyclic dependency among datagen assets: " + inCycle);
        }
        return ordered;
    }
}
