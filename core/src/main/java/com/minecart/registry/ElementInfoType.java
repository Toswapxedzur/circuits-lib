package com.minecart.registry;

import com.minecart.variant.ElementInfo;

import java.util.function.Supplier;

/**
 * Typed registry handle for an {@link ElementInfo} variety, mirroring {@link CircuitElementType}.
 * Holds a stable {@link #getTypeId() string id} (used as the on-disk key under
 * {@link com.minecart.misc.CoreStrings#INFOS}) and a no-arg {@link #create() factory} used during load.
 *
 * @param <I> concrete info subtype
 */
public class ElementInfoType<I extends ElementInfo> {

    protected final String id;
    protected final Supplier<I> factory;

    protected ElementInfoType(String id, Supplier<I> factory) {
        this.id = id;
        this.factory = factory;
    }

    /** Registry id (e.g. {@code "display:position"}). */
    public String getTypeId() {
        return id;
    }

    /** Constructs a fresh empty instance, used during load before {@link ElementInfo#load}. */
    public I create() {
        return factory.get();
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
