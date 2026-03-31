package com.minecart.action;

/**
 * Represent an Action conduct against a circuit element, only contain information, but not how to handle them
 */
public interface Action {

    /** Registry key used with {@link com.minecart.registry.CircuitElementType} handlers for this action. */
    ActionType<? extends Action> getActionType();
}
