package com.minecart.display.render.bounds;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.minecart.logic.CircuitElement;

@FunctionalInterface
public interface BoundingBoxProvider<T extends CircuitElement> {
    boolean contains(T element, Actor actor, float worldX, float worldY);
}
