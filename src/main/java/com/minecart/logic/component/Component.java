package com.minecart.logic.component;

import com.minecart.math.function.Expression;
import com.minecart.misc.ElectricalVariable;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public abstract class Component implements Comparable<Component>{
    public static final Comparator<? extends Component> comparator = (f, s) -> f.id.compareTo(s.id);
    protected UUID id;

    public Component(){
        this.id = UUID.randomUUID();
    }

    /**
     * A set of relationship between different current and voltages that helps figure out the final current and voltages
     *
     * @param equations Append equation representing limitations by overriding this method
     */
    public void collectRule(List<Expression> equations){

    }

    /**
     * Collect all the variables
     * @param variables All the data that could change and impacted by Rules
     */
    public void collectElectricalVariable(List<ElectricalVariable> variables){

    }

    @Override
    public int compareTo(Component o) {
        return o.id.compareTo(this.id);
    }
}
