package com.minecart.event.events;

import com.minecart.foundation.Level;
import com.minecart.logic.CircuitElement;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Posted once during {@link Level#init()}. Handlers should call {@link #register} with their
 * {@link com.minecart.logic.CircuitElement} callback; after the event completes, {@link Level} installs
 * {@link #getNotifier()} for {@link Level#notifyElementChanged}.
 */
public class RegisterElementChangeListenerEvent extends Event {

    private final Level level;

    private final List<Consumer<CircuitElement>> elementChangeRegistrations = new ArrayList<>();

    public RegisterElementChangeListenerEvent(Level level) {
        this.level = level;
    }

    public void register(Consumer<CircuitElement> listener){
        elementChangeRegistrations.add(listener);
    }

    public Consumer<CircuitElement> getNotifier(){
        return e -> {
            elementChangeRegistrations.forEach(consumer -> consumer.accept(e));
        };
    }

    public Level getLevel() {
        return level;
    }
}
