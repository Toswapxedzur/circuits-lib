package com.minecart.logic;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

public class Circuit {
    protected final UUID id;

    protected Set<CircuitNode> nodes;
    protected Set<CircuitEdge> edges;
    protected Set<CircuitComponent> components;

    public Circuit(UUID id) {
        this.id = id;
        nodes = new LinkedHashSet<>();
        edges = new LinkedHashSet<>();
        components = new LinkedHashSet<>();
    }
}
