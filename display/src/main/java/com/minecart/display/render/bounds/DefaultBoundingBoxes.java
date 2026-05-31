package com.minecart.display.render.bounds;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.minecart.display.render.EdgeActor;
import com.minecart.logic.CircuitComponent;
import com.minecart.logic.CircuitEdge;
import com.minecart.logic.CircuitNode;
import com.minecart.registry.AllElementInfos;
import com.minecart.variant.info.PositionInfo;

public final class DefaultBoundingBoxes {

    private DefaultBoundingBoxes() {}

    public static final BoundingBoxProvider<CircuitNode> NODE =
            (node, actor, worldX, worldY) -> actorContains(actor, worldX, worldY);

    public static final BoundingBoxProvider<CircuitComponent> COMPONENT =
            (component, actor, worldX, worldY) -> actorContains(actor, worldX, worldY);

    public static final BoundingBoxProvider<CircuitEdge> EDGE = (edge, actor, worldX, worldY) -> {
        CircuitNode n1 = edge.getConnection(0);
        CircuitNode n2 = edge.getConnection(1);
        if (n1 == null || n2 == null) {
            return false;
        }
        PositionInfo p1 = n1.getInfo(AllElementInfos.POSITION);
        PositionInfo p2 = n2.getInfo(AllElementInfos.POSITION);
        if (p1 == null || p2 == null) {
            return false;
        }
        float ax = (float) p1.getX();
        float ay = (float) p1.getY();
        float bx = (float) p2.getX();
        float by = (float) p2.getY();
        return pointToSegmentDistance(worldX, worldY, ax, ay, bx, by) <= EdgeActor.THICKNESS;
    };

    static void install() {
        // Defaults are exposed through class fallbacks. Type-specific registrations can override them.
    }

    private static boolean actorContains(Actor actor, float wx, float wy) {
        return actor != null
                && wx >= actor.getX() && wx <= actor.getX() + actor.getWidth()
                && wy >= actor.getY() && wy <= actor.getY() + actor.getHeight();
    }

    public static float pointToSegmentDistance(float px, float py,
                                               float ax, float ay,
                                               float bx, float by) {
        float dx = bx - ax;
        float dy = by - ay;
        float lenSq = dx * dx + dy * dy;
        if (lenSq < 1e-8f) {
            float ex = px - ax;
            float ey = py - ay;
            return (float) Math.sqrt(ex * ex + ey * ey);
        }
        float t = ((px - ax) * dx + (py - ay) * dy) / lenSq;
        if (t < 0f) t = 0f;
        else if (t > 1f) t = 1f;
        float fx = ax + t * dx;
        float fy = ay + t * dy;
        float ex = px - fx;
        float ey = py - fy;
        return (float) Math.sqrt(ex * ex + ey * ey);
    }
}
