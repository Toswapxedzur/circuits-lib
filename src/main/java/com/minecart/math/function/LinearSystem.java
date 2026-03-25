package com.minecart.math.function;

import org.ejml.data.DMatrixRMaj;
import org.ejml.data.DMatrixSparseCSC;
import org.ejml.data.DMatrixSparseTriplet;
import org.ejml.interfaces.linsol.LinearSolverSparse;
import org.ejml.ops.DConvertMatrixStruct;
import org.ejml.sparse.FillReducing;
import org.ejml.sparse.csc.factory.LinearSolverFactory_DSCC;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

public class LinearSystem {
    protected DMatrixSparseTriplet matrix;
    protected DMatrixSparseCSC column;
    protected DMatrixRMaj vectorB;
    protected DMatrixRMaj solutionX;

    protected LinearSolverSparse<DMatrixSparseCSC, DMatrixRMaj> solver;

    protected Set<DoubleVar> variables;

    public void collectVar(Consumer<Set<DoubleVar>> operator) {
        variables = new LinkedHashSet<>();
        operator.accept(variables);
    }

    public void init() {
        int index = 0;
        for (DoubleVar variable : variables) {
            variable.setIndex(index++);
        }

        int size = variables.size();
        if (size == 0) return;

        int estimatedNonZeros = size * 3;

        matrix = new DMatrixSparseTriplet(size, size, estimatedNonZeros);
        column = new DMatrixSparseCSC(size, size, estimatedNonZeros);
        vectorB = new DMatrixRMaj(size, 1);
        solutionX = new DMatrixRMaj(size, 1);

        solver = LinearSolverFactory_DSCC.lu(FillReducing.NONE);
    }

    public void stampRelation(Consumer<RelationProvider> provider) {
        if (variables == null || variables.isEmpty()) return;

        matrix.nz_length = 0;
        vectorB.zero();

        RelationProvider relationProvider = new LinearReceiver(this);
        provider.accept(relationProvider);
    }

    public boolean solve() {
        if (variables == null || variables.isEmpty()) return true;

        DConvertMatrixStruct.convert(matrix, column);

        if (!solver.setA(column)) {
            return false;
        }

        solver.solve(vectorB, solutionX);

        // 5. UPDATE: Read the raw primitive double answers back into the Variable objects
        for (DoubleVar var : variables) {
            var.setValue(solutionX.get(var.getIndex(), 0));
        }

        return true;
    }

    public static interface RelationProvider {
        void stampCoefficient(DoubleVar variable, double coef);

        void stampConstant(double constant);

        void endRelation();
    }

    public static class LinearReceiver implements RelationProvider {
        protected LinearSystem system;
        protected int currentRow = 0;

        public LinearReceiver(LinearSystem system) {
            this.system = system;
        }

        @Override
        public void stampCoefficient(DoubleVar variable, double coef) {
            if (variable != null && variable.getIndex() >= 0) {
                // Instantly drops the primitive double into the underlying array
                system.matrix.addItem(currentRow, variable.getIndex(), coef);
            }
        }

        @Override
        public void stampConstant(double constant) {
            system.vectorB.set(currentRow, 0, constant);
        }

        @Override
        public void endRelation() {
            currentRow++;
        }
    }
}