package com.minecart.component;

import com.minecart.logic.CircuitEdge;
import com.minecart.math.function.Expression;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.minecart.math.function.Expression.ExpressionBuilder.*;

public class Junction extends CircuitNode{
    protected List<CircuitEdge> edges;

    public Junction(){
        edges = new ArrayList<>();
    }

    @Override
    public void collectRule(List<Expression> equations) {
        super.collectRule(equations);

        //same voltage
        CircuitEdge last = null;
        for(CircuitEdge edge : getConnection()){
            if(last == null) {
                last = edge;
                continue;
            }
            Expression exp = sub(variable(edge.getVoltage()), variable(last.getVoltage()));
            last = edge;
            equations.add(exp);
        }
    }

    @Override
    public boolean connectEdge(CircuitEdge other, boolean simulate){
        if(!simulate)
            edges.add(other);
        return true;
    }

    @Override
    public boolean disconnect(CircuitEdge other, boolean simulate) {
        if(simulate)
            return edges.contains(other);
        return edges.remove(other);
    }

    @Override
    public void getConnectionGroup(List<List<CircuitEdge>> groups) {
        List<CircuitEdge> circuitEdge = new ArrayList<>();
        for(CircuitEdge edge : edges){
            circuitEdge.add(edge);
        }
        groups.add(circuitEdge);
    }
}
