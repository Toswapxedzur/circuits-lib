package com.minecart.logic;

import java.util.Comparator;
import java.util.UUID;

public abstract class Component implements Comparable<Component>{
    public static final Comparator<? extends Component> comparator = (f, s) -> f.id.compareTo(s.id);
    protected boolean open;
    protected UUID id;

    public Component(){
        this.id = UUID.randomUUID();
        this.open = false;
    }

    public boolean isOpen() {
        return open;
    }

    public void setOpen(boolean open) {
        this.open = open;
    }

    @Override
    public int compareTo(Component o) {
        return o.id.compareTo(this.id);
    }
}
