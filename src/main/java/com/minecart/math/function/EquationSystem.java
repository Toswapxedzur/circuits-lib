package com.minecart.math.function;

import org.apache.commons.lang3.tuple.Pair;

import java.util.List;
import java.util.Set;

public class EquationSystem {
    //default assume the equation is expression = 0
    List<Expression> system;
    Set<Pair<Variable<Double>, Double>> variables;

    public EquationSystem(List<Expression> system){
        this.system = system;
        this.variables = Set.of();
    }

    public int size(){
        return system.size();
    }

    public Expression get(int i){
        return system.get(i);
    }
}
