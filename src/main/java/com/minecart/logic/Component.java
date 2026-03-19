package com.minecart.logic;

import com.minecart.math.function.Expression;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
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

    /**
     * A set of relationship between different current and voltages that helps figure out the final current and voltages
     *
     * @param equations Append equation representing limitations by overriding this method
     */
    public void collectRule(List<Expression> equations){

    }

    @Override
    public int compareTo(Component o) {
        return o.id.compareTo(this.id);
    }
}
