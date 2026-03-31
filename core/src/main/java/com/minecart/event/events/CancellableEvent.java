package com.minecart.event.events;

public class CancellableEvent extends Event{
    boolean cancel;

    public boolean isCancel() {
        return cancel;
    }

    public void setCancel() {
        this.cancel = true;
    }
}
