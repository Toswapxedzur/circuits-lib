package com.minecart.event.events;

import com.minecart.foundation.World;
import com.minecart.logic.CircuitElement;
import com.minecart.registry.ElementInfoType;
import com.minecart.variant.ElementInfo;

/**
 * Fired once per {@link CircuitElement}, after the element exists but before topology commits or saved
 * fields are loaded. Listeners use {@link #inject(ElementInfoType, ElementInfo)} to attach an
 * {@link ElementInfo} of any registered {@link ElementInfoType} to the element.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li><b>Creation:</b> fired in {@link com.minecart.logic.ServerWorld#createNode},
 *       {@link com.minecart.logic.ServerWorld#connect}, and similar paths after the element instance is
 *       built but before {@link ElementEvent.ElementInsertEvent} runs.</li>
 *   <li><b>Load:</b> fired in {@link com.minecart.foundation.Circuit#load} per element after deserialization
 *       but before {@link CircuitElement#load} reads the saved fields, so persisted infos override the
 *       defaults injected here.</li>
 * </ul>
 *
 * <p>Per the data-component convention, each {@link ElementInfoType} key holds at most one info per element;
 * a later {@link #inject(ElementInfoType, ElementInfo) inject} call overwrites an earlier one.
 */
public class ElementInfoInjectEvent extends Event {

    private final World world;
    private final CircuitElement element;

    public ElementInfoInjectEvent(World world, CircuitElement element) {
        this.world = world;
        this.element = element;
    }

    public World getWorld() {
        return world;
    }

    public CircuitElement getElement() {
        return element;
    }

    /** Convenience: attaches {@code info} to the element under {@code type}. */
    public <I extends ElementInfo> void inject(ElementInfoType<I> type, I info) {
        element.setInfo(type, info);
    }

    /** @return {@code true} if some prior listener already injected an info for {@code type}. */
    public boolean isPresent(ElementInfoType<?> type) {
        return element.getInfo(type) != null;
    }
}
