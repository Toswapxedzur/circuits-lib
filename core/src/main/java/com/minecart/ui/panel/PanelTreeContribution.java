package com.minecart.ui.panel;

import com.minecart.logic.CircuitElement;

@FunctionalInterface
public interface PanelTreeContribution<T extends CircuitElement> {
    void contribute(T element, PanelTreeBuilder<T> builder);
}
