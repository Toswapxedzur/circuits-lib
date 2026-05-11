package com.minecart.variant;

/**
 * Represent an information that an electrical component held, only added after creation
 * @param <O> Type of electrical information
 */
public interface ElectricalVariate<O extends ElectricalInfo> {
    O get();

    /**
     * Should be treated as a static method
     */
    O getDefault();

    boolean hasProperty(int index);

    Object getProperty(int index);

    /** Replaces the electrical parameter bag for this element. */
    void set(O property);

    /**
     * Sets a single indexed property (same indices as {@link #getProperty(int)} / {@link #hasProperty(int)}).
     */
    void set(int index, Object property);
}
