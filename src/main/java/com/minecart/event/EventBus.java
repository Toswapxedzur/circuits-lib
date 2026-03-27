package com.minecart.event;

import com.minecart.event.events.CancellableEvent;
import com.minecart.event.events.Event;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class EventBus {

    private final Map<Class<? extends Event>, List<Consumer<?>>> handlers = new HashMap<>();

    public <T extends Event> void register(Class<T> eventClass, Consumer<T> listener) {
        handlers.computeIfAbsent(eventClass, k -> new ArrayList<>()).add(listener);
    }

    @SuppressWarnings("unchecked")
    public boolean post(Event event) {
        List<Consumer<?>> eventListeners = handlers.get(event.getClass());

        if (eventListeners != null) {
            for (Consumer<?> listener : eventListeners) {
                ((Consumer<Event>) listener).accept(event);

                if (event instanceof CancellableEvent cancellable && cancellable.isCancel()) {
                    return true;
                }
            }
        }

        return event instanceof CancellableEvent cancellable && cancellable.isCancel();
    }
}