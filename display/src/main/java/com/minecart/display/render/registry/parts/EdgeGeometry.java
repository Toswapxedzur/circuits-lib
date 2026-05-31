package com.minecart.display.render.registry.parts;

import com.minecart.display.render.EdgeActor;
import com.minecart.logic.CircuitEdge;
import com.minecart.logic.CircuitNode;
import com.minecart.registry.AllElementInfos;
import com.minecart.variant.info.PositionInfo;

final class EdgeGeometry {

    final float ax;
    final float ay;
    final float bx;
    final float by;
    final float len;
    final float angleDeg;
    final float ux;
    final float uy;
    final float bodyStartX;
    final float bodyStartY;
    final float bodyEndX;
    final float bodyEndY;
    final float bodyLen;
    final float bridgeEach;

    private EdgeGeometry(float ax, float ay, float bx, float by, float len,
                         float angleDeg, float ux, float uy,
                         float bodyStartX, float bodyStartY,
                         float bodyEndX, float bodyEndY,
                         float bodyLen, float bridgeEach) {
        this.ax = ax;
        this.ay = ay;
        this.bx = bx;
        this.by = by;
        this.len = len;
        this.angleDeg = angleDeg;
        this.ux = ux;
        this.uy = uy;
        this.bodyStartX = bodyStartX;
        this.bodyStartY = bodyStartY;
        this.bodyEndX = bodyEndX;
        this.bodyEndY = bodyEndY;
        this.bodyLen = bodyLen;
        this.bridgeEach = bridgeEach;
    }

    static EdgeGeometry of(CircuitEdge edge, float bodyNaturalLen) {
        CircuitNode a = edge.getConnection(0);
        CircuitNode b = edge.getConnection(1);
        if (a == null || b == null) {
            return null;
        }
        PositionInfo pa = a.getInfo(AllElementInfos.POSITION);
        PositionInfo pb = b.getInfo(AllElementInfos.POSITION);
        if (pa == null || pb == null) {
            return null;
        }
        float ax = (float) pa.getX();
        float ay = (float) pa.getY();
        float bx = (float) pb.getX();
        float by = (float) pb.getY();
        float dx = bx - ax;
        float dy = by - ay;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-4f) {
            return null;
        }
        float angleDeg = (float) Math.toDegrees(Math.atan2(dy, dx));
        float bodyLen = Math.min(len, bodyNaturalLen);
        float bridgeEach = (len - bodyLen) * 0.5f;
        float ux = dx / len;
        float uy = dy / len;
        float bodyStartX = ax + ux * bridgeEach;
        float bodyStartY = ay + uy * bridgeEach;
        float bodyEndX = bodyStartX + ux * bodyLen;
        float bodyEndY = bodyStartY + uy * bodyLen;
        return new EdgeGeometry(ax, ay, bx, by, len, angleDeg, ux, uy,
                bodyStartX, bodyStartY, bodyEndX, bodyEndY, bodyLen, bridgeEach);
    }

    static float naturalBodyLength(com.badlogic.gdx.graphics.Texture tex) {
        float texAspect = tex.getHeight() > 0
                ? (float) tex.getWidth() / (float) tex.getHeight()
                : 4f;
        return Math.min(EdgeActor.THICKNESS * texAspect, EdgeActor.MAX_TEXTURE_LEN);
    }
}
