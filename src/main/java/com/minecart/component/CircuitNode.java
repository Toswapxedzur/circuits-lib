package com.minecart.component;

import com.google.common.cache.Cache;
import com.minecart.action.ActionType;
import com.minecart.action.ElectricalAction;
import com.minecart.logic.CircuitEdge;
import com.minecart.math.function.Expression;
import com.minecart.misc.CurrentFlow;
import com.minecart.variant.type.ElectricalInformation;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class CircuitNode extends Component {
    protected boolean dirty;
    List<List<CircuitEdge>> cachedEdge;

    protected CircuitNode(){
    }

    protected CircuitNode(ElectricalInformation info){
    }

    protected final Map<ActionType<?>, Consumer<? extends ElectricalAction>> actionHandlers = new HashMap<>();

    protected <A extends ElectricalAction> void addActionHandler(ActionType<A> type, Consumer<A> handler) {
        actionHandlers.put(type, handler);
    }

    public <A extends ElectricalAction> boolean perform(ActionType<A> type, A action) {
        Consumer<A> handler = (Consumer<A>) actionHandlers.get(type);
        if (handler != null) {
            handler.accept(action);
            return true;
        }
        return false;
    }

    @Override
    public void collectRule(List<Expression> equations) {
        super.collectRule(equations);

        //default kirchoff current rule
        List<Expression> currentSum = new ArrayList<>();
        for(CircuitEdge edge : getConnection()){
            Expression exp = Expression.ExpressionBuilder.variable(edge.getCurrent());
            if(edge.shouldRevert(this))
                exp = Expression.ExpressionBuilder.neg(exp);
            if(!edge.selfLoop())
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
    public List<CircuitEdge> getConnection(){
        List<List<CircuitEdge>> groups = new ArrayList<>();
        getConnectionGroup(groups);

        int totalEdges = 0;
        for (int i = 0; i < groups.size(); i++) {
            totalEdges += groups.get(i).size();
        }

        List<CircuitEdge> flattened = new ArrayList<>(totalEdges);

        for (int i = 0; i < groups.size(); i++) {
            flattened.addAll(groups.get(i));
        }

        return flattened;
    }

    public void getConnectionGroup(List<List<CircuitEdge>> groups){
    }

    public boolean connectEdge(CircuitEdge egde, boolean simulate){
        return false;
    }

    public List<CircuitEdge> getInConnection(){
        return getConnection().stream().filter(e -> e.flowDirection(this).equals(CurrentFlow.IN)).collect(Collectors.toList());
    }

    public List<CircuitEdge> getOutConnection(){
        return getConnection().stream().filter(e -> e.flowDirection(this).equals(CurrentFlow.OUT)).collect(Collectors.toList());
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

    protected List<CircuitNode> getConnectedNodes(List<CircuitEdge> circuitEdges){
        List<CircuitNode> list = new ArrayList<>();
        for(CircuitEdge iterEdge : circuitEdges){
            if(iterEdge.getConnection(0) == this)
                list.add(iterEdge.getConnection(1));
            else
                list.add(iterEdge.getConnection(0));
        }
        return list;
    }

    public List<CircuitNode> adjacentNode(){
        return getConnectedNodes(getConnection());
    }

    public List<CircuitNode> adjacentInNode(){
        return getConnectedNodes(getInConnection());
    }

    public List<CircuitNode> adjacentOutNode(){
        return getConnectedNodes(getOutConnection());
    }
}
