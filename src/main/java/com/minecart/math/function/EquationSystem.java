package com.minecart.math.function;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import org.apache.commons.math3.linear.*;
import org.apache.commons.math3.util.Pair;

import java.util.*;

public class EquationSystem {
    //default assume the equation is expression = 0
    List<Expression> system;

    public EquationSystem(List<Expression> system){
        this.system = system;
    }

    public int size(){
        return system.size();
    }

    public Expression get(int i){
        return system.get(i);
    }

    public boolean isLinear(){
        for(Expression expression : system){
            expression.simplify();
            if(!expression.isLinear())
                return false;
        }
        return true;
    }

    public SolutionState solveLinear() {
        if(!isLinear())
            return SolutionState.UNSOL;
        Set<DoubleVariable> varCollection = new LinkedHashSet<>();
        BiMap<Integer, DoubleVariable> varMap = HashBiMap.create();
        for(Expression equation : system){
            equation.collectVar(varCollection);
        }
        int index = 0;
        for(DoubleVariable variable : varCollection){
            varMap.put(index++, variable);
        }

        int numEquations = system.size();
        int numVariables = varCollection.size();

        RealMatrix A = new OpenMapRealMatrix(numEquations, numVariables);
        RealVector b = new ArrayRealVector(numEquations);

        // 2. Stamp the AST data
        for (int i = 0; i < numEquations; i++) {
            Expression equation = system.get(i);

            Pair<List<org.apache.commons.math3.util.Pair<Double, DoubleVariable>>, Double> linearData = equation.toLinear();
            List<Pair<Double, DoubleVariable>> terms = linearData.getKey();
            double intercept = linearData.getValue();

            for (Pair<Double, DoubleVariable> term : terms) {
                A.setEntry(i, varMap.inverse().get(term.getValue()), term.getKey());
            }

            b.setEntry(i, -intercept);
        }

        DecompositionSolver solver = new QRDecomposition(A).getSolver();

        if (!solver.isNonSingular()) {
            return SolutionState.NOSOL; // Matrix is singular, no unique solution exists
        }

        RealVector solution = solver.solve(b);

        for (int i = 0; i < numVariables; i++) {
            DoubleVariable variable = varMap.get(i);
            variable.setValue(solution.getEntry(i));
        }
        return SolutionState.SOL;
    }

    public enum SolutionState{
        UNSOL, NOSOL, SOL
    }
}
