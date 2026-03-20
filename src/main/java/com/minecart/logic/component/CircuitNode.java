package com.minecart.logic.component;

import com.minecart.logic.Circuit;
import com.minecart.misc.CurrentFlow;
import com.minecart.math.function.Expression;

import java.util.*;
import java.util.stream.Collectors;

public class CircuitNode extends Component {

    public CircuitNode(){
    }

    @Override
    public void collectRule(List<Expression> equations) {
        super.collectRule(equations);

        //kirchoff current rule
        List<Expression> currentSum = new ArrayList<>();
        for(CircuitEdge edge : getConnection()){
            Expression exp = Expression.ExpressionBuilder.var(edge.getCurrent());
            if(edge.shouldRevert(this))
                exp = Expression.ExpressionBuilder.neg(exp);
            currentSum.add(exp);
        }
        if(currentSum.size() > 0)
            equations.add(Expression.ExpressionBuilder.add(currentSum));
    }

    @Override
    public void tick(){

    }

    public boolean disconnect(CircuitEdge other, boolean simulate){
        return false;
    }

    public boolean destroy(List<CircuitEdge> destroyedEdge, boolean simulate){
        return true;
    }

    //override this method for connections
    public Set<CircuitEdge> getConnection(){
        return new HashSet<>();
    }

    public boolean connectEdge(CircuitEdge egde, boolean simulate){
        return false;
    }

    public Set<CircuitEdge> getInConnection(){
        return getConnection().stream().filter(e -> e.flowDirection(this).equals(CurrentFlow.IN)).collect(Collectors.toSet());
    }

    public Set<CircuitEdge> getOutConnection(){
        return getConnection().stream().filter(e -> e.flowDirection(this).equals(CurrentFlow.OUT)).collect(Collectors.toSet());
    }

    public int getAmountConnected(){
        return getConnection().size();
    }

    public int getInAmountConnected(){
        return getInConnection().size();
    }

    public int getOutAmountConnected(){
        return getOutConnection().size();
    }

    protected Set<CircuitNode> getConnectedNodes(Set<CircuitEdge> circuitEdges){
        Set<CircuitNode> set = new TreeSet<>();
        for(CircuitEdge iterEdge : circuitEdges){
            if(iterEdge.getConnection(0) == this)
                set.add(iterEdge.getConnection(1));
            else
                set.add(iterEdge.getConnection(0));
        }
        return set;
    }

    public Set<CircuitNode> adjacentNode(){
        return getConnectedNodes(getConnection());
    }

    public Set<CircuitNode> adjacentInNode(){
        return getConnectedNodes(getInConnection());
    }

    public Set<CircuitNode> adjacentOutNode(){
        return getConnectedNodes(getOutConnection());
    }
}
