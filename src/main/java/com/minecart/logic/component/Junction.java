package com.minecart.logic.component;

import com.minecart.logic.Circuit;
import com.minecart.math.function.Expression;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
            if(last == null)
                continue;
            Expression exp = Expression.ExpressionBuilder.sub(
                    Expression.ExpressionBuilder.var(edge.voltage),
                    Expression.ExpressionBuilder.var(last.voltage));
            last = edge;
            equations.add(exp);
        }
    }

    @Override
    public boolean connectEdge(CircuitEdge other, boolean simulate){
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
    public Set<CircuitEdge> getConnection() {
        Set<CircuitEdge> circuitEdges = super.getConnection();
        for(CircuitEdge edge : edges){
            circuitEdges.add(edge);
        }
        return circuitEdges;
    }
}
