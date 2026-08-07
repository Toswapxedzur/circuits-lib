package com.minecart.display.render.registry;

import com.minecart.logic.CircuitEdge;
import com.minecart.logic.CircuitNode;

/**
 * Per-frame electrical magnitudes used only for display normalization. Edge and node scales are kept
 * separate so dense junctions do not wash out individual wire currents.
 */
public final class ElectricalRenderStats {

    private static final double EPSILON = 1e-9;

    public static final ElectricalRenderStats EMPTY = new ElectricalRenderStats(0.0, 0.0);

    private final double maxEdgeCurrent;
    private final double maxNodeTraffic;

    public ElectricalRenderStats(double maxEdgeCurrent, double maxNodeTraffic) {
        this.maxEdgeCurrent = Math.max(0.0, maxEdgeCurrent);
        this.maxNodeTraffic = Math.max(0.0, maxNodeTraffic);
    }

    public double maxEdgeCurrent() {
        return maxEdgeCurrent;
    }

    public double maxNodeTraffic() {
        return maxNodeTraffic;
    }

    public float edgeIntensity(CircuitEdge edge) {
        return logIntensity(Math.abs(edge.getCurrent().getValue()), maxEdgeCurrent);
    }

    public float nodeIntensity(CircuitNode node) {
        return logIntensity(nodeTraffic(node), maxNodeTraffic);
    }

    public static double nodeTraffic(CircuitNode node) {
        double in = 0.0;
        double out = 0.0;
        for (CircuitEdge edge : node.getConnection()) {
            double current = edge.getCurrent().getValue();
            double magnitude = Math.abs(current);
            if (magnitude <= EPSILON) {
                continue;
            }
            if (edge.getSource() == node) {
                out += magnitude;
            } else {
                in += magnitude;
            }
        }
        return 2.0 * Math.max(in, out);
    }

    private static float logIntensity(double value, double max) {
        if (value <= EPSILON || max <= EPSILON) {
            return 0f;
        }
        double t = Math.log1p(value / EPSILON) / Math.log1p(max / EPSILON);
        return (float) Math.max(0.0, Math.min(1.0, t));
    }
}
