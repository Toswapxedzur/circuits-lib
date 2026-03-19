package com.minecart.math.function;

import org.apache.commons.math3.linear.*;
import org.apache.commons.math3.util.Pair;

import java.util.*;

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

    public boolean isLinear(){
        for(Expression expression : system){
            expression.simplify();
            if(!expression.isLinear())
                return false;
        }
        return true;
    }

    public boolean solveLinear() {
        if(!isLinear())
            return false;
        Set<Variable<Double>> varCollection = new TreeSet<>();
        Map<Integer, Variable<Double>> varMap = new TreeMap<>();
        for(Expression equation : system){
            equation.collectVar(varCollection);
        }
        int index = 0;
        for(Variable<Double> variable : varCollection){
            variable.index = ++index;
            varMap.put(variable.index, variable);
        }

        int numEquations = system.size();
        int numVariables = varCollection.size();

        RealMatrix A = new OpenMapRealMatrix(numEquations, numVariables);
        RealVector b = new ArrayRealVector(numEquations);

        // 2. Stamp the AST data
        for (int i = 0; i < numEquations; i++) {
            Expression equation = system.get(i);

            Pair<List<org.apache.commons.math3.util.Pair<Double, Variable<Double>>>, Double> linearData = equation.toLinear();
            List<Pair<Double, Variable<Double>>> terms = linearData.getKey();
            double intercept = linearData.getValue();

            for (Pair<Double, Variable<Double>> term : terms) {
                A.setEntry(i, term.getValue().index, term.getKey());
            }

            b.setEntry(i, -intercept);
        }

        DecompositionSolver solver = new QRDecomposition(A).getSolver();

        if (!solver.isNonSingular()) {
            return false; // Matrix is singular, no unique solution exists
        }

        RealVector solution = solver.solve(b);

        for (int i = 0; i < numVariables; i++) {
            Variable<Double> variable = varMap.get(i);
            variable.setValue(solution.getEntry(i));
        }
        return true;
    }
}
