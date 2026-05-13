package com.minecart.display.editor;

import com.minecart.registry.AllComponents;
import com.minecart.registry.CircuitElementType;

import java.util.List;

/**
 * Static catalogue of element kinds the singleplayer editor lets the user place, with their display name and
 * which {@link Kind} of tool selecting them activates. Stays in sync with {@link AllComponents}: anything
 * registered there that should be place-able by the user is mirrored here.
 */
public final class PaletteEntries {

    /** Whether selecting this entry yields a node, edge, or component tool. */
    public enum Kind {
        NODE, EDGE, COMPONENT
    }

    public record Entry(CircuitElementType<?> type, String displayName, Kind kind) {}

    public static final List<Entry> ALL = List.of(
            new Entry(AllComponents.CONNECTION, "Junction", Kind.NODE),
            new Entry(AllComponents.JUNCTION, "Junction (Y)", Kind.NODE),
            new Entry(AllComponents.RESISTOR, "Resistor", Kind.EDGE),
            new Entry(AllComponents.BATTERY, "Battery", Kind.EDGE),
            new Entry(AllComponents.CAPACITOR, "Capacitor", Kind.EDGE),
            new Entry(AllComponents.DIODE, "Diode", Kind.EDGE),
            new Entry(AllComponents.BJ_TRANSISTOR, "BJ Transistor", Kind.COMPONENT)
    );

    private PaletteEntries() {}
}
