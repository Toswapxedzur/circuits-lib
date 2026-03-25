package com.minecart.math.function;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Could also be used to represent an equation with exp = 0
 */
@Deprecated
public class Expression {
    protected List<Expression> children;
    protected Operator operator;
    protected DoubleVar variable;
    protected double constant;
    protected boolean leaf;

    public Expression(Operator operator, List<Expression> children) {
        this.operator = operator;
        // Wrapping in ArrayList ensures the internal list is always mutable
        this.children = new ArrayList<>(children);
        this.variable = null;
        this.constant = 0.0;
        this.leaf = false;

        if (!operator.target.targetAmount.test(children.size())) {
            throw new IllegalArgumentException("Expression amount incorrect");
        }
    }

    public Expression(DoubleVar variable) {
        this.operator = null;
        this.children = new ArrayList<>();
        this.variable = variable;
        this.constant = 0.0;
        this.leaf = true;
    }

    public Expression(double constant) {
        this.operator = null;
        this.children = new ArrayList<>();
        this.variable = null;
        this.constant = constant;
        this.leaf = true;
    }

    public void collectVar(Set<DoubleVar> set) {
        if (leaf) {
            if (variable != null) {
                set.add(variable);
            }
        } else {
            for (Expression child : children) {
                child.collectVar(set);
            }
        }
    }

    public void simplify(){
        if (leaf) {
            return;
        }

        for (Expression child : children) {
            child.simplify();
        }

        simplifyCurrent();
    }

    protected void simplifyCurrent() {
        if (leaf) return;
        flattenAssociative();
        foldConstants();
        applyIdentities();
        expandTerms();
    }

    private void flattenAssociative() {
        if (operator instanceof MultiOperator multiOperator) {
            List<Expression> flatChildren = new ArrayList<>();
            for (Expression child : children) {
                if (!child.leaf && child.operator == multiOperator) {
                    flatChildren.addAll(child.children);
                } else {
                    flatChildren.add(child);
                }
            }
            this.children = flatChildren;
        }
    }

    private void applyIdentities() {
        if (leaf) return;

        if (operator == Operator.MULTIPLICATION) {
            if (hasConstant(0.0)) transformToLeaf(0.0);
            else removeConstants(1.0);
        } else if (operator == Operator.ADDITION) {
            removeConstants(0.0);
        } else if (operator == Operator.POWER) {
            if (isConstant(children.get(1), 0.0)) transformToLeaf(1.0);
            else if (isConstant(children.get(1), 1.0)) replaceWith(children.get(0));
            else if (isConstant(children.get(0), 0.0)) transformToLeaf(0.0);
        }

        if (!leaf && children.size() == 1 && operator.target == DoubleOperator.Target.MULTIPLE) {
            replaceWith(children.get(0));
        }
    }

    private void transformToLeaf(double val) {
        this.operator = null;
        this.children = new ArrayList<>();
        this.constant = val;
        this.variable = null;
        this.leaf = true;
    }

    private void replaceWith(Expression other) {
        this.operator = other.operator;
        this.children = other.children;
        this.constant = other.constant;
        this.variable = other.variable;
        this.leaf = other.leaf;
    }

    private boolean hasConstant(double targetValue) {
        return children.stream().anyMatch(c -> isConstant(c, targetValue));
    }

    private boolean isConstant(Expression expr, double targetValue) {
        return expr.leaf && expr.variable == null && expr.constant == targetValue;
    }

    private boolean isVariable() {
        return this.leaf && this.variable != null;
    }

    private boolean isConstant() {
        return this.leaf && !isVariable();
    }

    private void removeConstants(double targetValue) {
        this.children = children.stream()
                .filter(c -> !isConstant(c, targetValue))
                .collect(Collectors.toList());
    }

    private void extractLinearTerms(Map<DoubleVar, Double> termMap, List intercept) {
        if (leaf) {
            if (isConstant()) {
                intercept.add(constant);
            } else if (variable != null) {
                termMap.merge(variable, 1.0, Double::sum);
            }
            return;
        }

        // Completely removed the baseOp reflection hack!
        if (operator == Operator.ADDITION) {
            for (Expression child : children) {
                child.extractLinearTerms(termMap, intercept);
            }
        } else if (operator == Operator.MULTIPLICATION) {
            double coeff = 1.0;
            DoubleVar var = null;

            for (Expression child : children) {
                if (child.leaf && child.isConstant()) {
                    coeff *= child.constant;
                } else if (child.leaf && child.variable != null) {
                    var = child.variable;
                }
            }

            if (var != null) {
                termMap.merge(var, coeff, Double::sum);
            } else {
                intercept.add(coeff);
            }
        }
    }

    public boolean isLinear() {
        if (leaf) return true;

        if (operator == Operator.ADDITION) {
            for (Expression child : children) {
                if (!child.isLinear())
                    return false;
            }
            return true;
        } else if (operator == Operator.MULTIPLICATION) {
            int varCount = 0;
            for (Expression child : children) {
                if (child.leaf && child.isConstant()) {
                    continue;
                } else if (child.leaf && child.variable != null) {
                    varCount++;
                } else {
                    return false;
                }
            }
            return varCount <= 1;
        }
        return false;
    }

    private void foldConstants() {
        List<Expression> consts = new ArrayList<>();
        List<Expression> vars = new ArrayList<>();

        for (Expression child : children) {
            if (child.leaf && child.isConstant()) consts.add(child);
            else vars.add(child);
        }

        if (consts.isEmpty() || (consts.size() == 1 && !vars.isEmpty())) return;

        double foldedValue = 0.0;
        if(operator instanceof DoubleOperator doubleOperator && doubleOperator.target.targetAmount.test(2)) {
            foldedValue = doubleOperator.operator.applyAsDouble(consts.get(0).constant, consts.get(1).constant);
        }else if(operator instanceof DoubleOperator doubleOperator && doubleOperator.target.targetAmount.test(1)){
            foldedValue = doubleOperator.operator.applyAsDouble(consts.get(0).constant, 0.0);
        } else if(operator instanceof MultiOperator multiOperator){
            foldedValue = multiOperator.multiOperator.apply(
                    consts.stream().mapMultiToDouble((e, consumer) -> consumer.accept(e.constant)).toArray());
        }else{
            throw new IllegalArgumentException("Strange Operator while folding constants");
        }

        if (Double.isInfinite(foldedValue) || Double.isNaN(foldedValue)) {
            throw new ArithmeticException("Mathematical error during constant folding such as Division by Zero");
        }

        if (vars.isEmpty()) {
            transformToLeaf(foldedValue);
        } else {
            vars.add(new Expression(foldedValue));
            this.children = vars;
        }
    }

    private void expandTerms() {
        if (leaf) return;

        if (operator == Operator.MULTIPLICATION) {
            List<Expression> sumNodes = new ArrayList<>();
            List<Expression> otherNodes = new ArrayList<>();

            for (Expression child : children) {
                if (!child.leaf && child.operator == Operator.ADDITION) {
                    sumNodes.add(child);
                } else {
                    otherNodes.add(child);
                }
            }

            if (sumNodes.isEmpty()) return;

            if (sumNodes.size() >= 2) {
                Expression s1 = sumNodes.get(0);
                Expression s2 = sumNodes.get(1);
                List<Expression> expandedTerms = new ArrayList<>();

                for (Expression term1 : s1.children) {
                    for (Expression term2 : s2.children) {
                        // Directly assign native Operator.MULTIPLICATION
                        expandedTerms.add(new Expression(Operator.MULTIPLICATION,
                                Arrays.asList(term1.deepCopy(), term2.deepCopy())));
                    }
                }

                for(Expression term : expandedTerms){
                    term.simplifyCurrent();
                }

                // Directly assign native Operator.ADDITION
                Expression newSum = new Expression(Operator.ADDITION, expandedTerms);

                List<Expression> newChildren = new ArrayList<>();
                newChildren.add(newSum);
                for (int i = 2; i < sumNodes.size(); i++) {
                    newChildren.add(sumNodes.get(i));
                }
                newChildren.addAll(otherNodes);

                if (newChildren.size() == 1) {
                    this.replaceWith(newChildren.get(0));
                } else {
                    this.children = newChildren;
                    this.expandTerms();
                }
                return;
            }

            else if (sumNodes.size() == 1 && !otherNodes.isEmpty()) {
                Expression sumNode = sumNodes.get(0);
                List<Expression> distributedTerms = new ArrayList<>();

                for (Expression term : sumNode.children) {
                    List<Expression> newMultipliers = new ArrayList<>();
                    newMultipliers.add(term.deepCopy());

                    for (Expression other : otherNodes) {
                        newMultipliers.add(other.deepCopy());
                    }

                    // Directly assign native Operator.MULTIPLICATION
                    distributedTerms.add(new Expression(Operator.MULTIPLICATION, newMultipliers));
                }

                for(Expression term : distributedTerms){
                    term.simplifyCurrent();
                }

                // Directly assign native Operator.ADDITION
                this.operator = Operator.ADDITION;
                this.children = distributedTerms;
            }
        }
    }

    /**
     * Recursively creates a fresh instance of the AST branch.
     * This is strictly required when distributing terms so mutable nodes don't share memory references.
     */
    public Expression deepCopy() {
        if (leaf) {
            if (isConstant()) return new Expression(constant);
            return new Expression(variable);
        }

        List<Expression> copiedChildren = new ArrayList<>();
        for (Expression child : children) {
            copiedChildren.add(child.deepCopy());
        }

        return new Expression(operator, copiedChildren);
    }

    public static class ImmutableExpression extends Expression{
        public ImmutableExpression(Operator operator, List<Expression> children) {
            super(operator, children);
            this.children = Collections.unmodifiableList(this.children);
        }

        public ImmutableExpression(DoubleVar variable) {
            super(variable);
            this.children = Collections.unmodifiableList(this.children);
        }

        public ImmutableExpression(double constant) {
            super(constant);
            this.children = Collections.unmodifiableList(this.children);
        }

        /**
         * Deeply clones a mutable Expression tree into a permanently locked Immutable tree.
         */
        public static ImmutableExpression freeze(Expression expr) {
            if (expr instanceof ImmutableExpression) {
                return (ImmutableExpression) expr; // Already safe
            }

            if (expr.leaf) {
                if (expr.isConstant()) {
                    return new ImmutableExpression(expr.constant);
                }
                return new ImmutableExpression(expr.variable);
            }

            List<Expression> frozenChildren = expr.children.stream()
                    .map(ImmutableExpression::freeze)
                    .collect(Collectors.toList());

            return new ImmutableExpression(expr.operator, frozenChildren);
        }

        @Override
        public void simplify() {
            throw new UnsupportedOperationException("Cannot simplify an ImmutableExpression.");
        }

        @Override
        protected void simplifyCurrent() {
            throw new UnsupportedOperationException("Cannot mutate an ImmutableExpression.");
        }
    }

    public static final class ExpressionBuilder{
        private ExpressionBuilder() {}

        public static Expression variable(DoubleVar variable) {
            return new Expression(variable);
        }

        public static Expression value(double constant) {
            return new Expression(constant);
        }

        public static Expression add(Expression... children) {
            return add(Arrays.asList(children));
        }

        public static Expression add(List<Expression> children) {
            return new Expression(Operator.ADDITION, children);
        }

        public static Expression sub(Expression left, Expression right){
            return add(left, mul(value(-1.0), right));
        }

        public static Expression mul(Expression... children) {
            return mul(Arrays.asList(children));
        }

        public static Expression mul(List<Expression> children) {
            return new Expression(Operator.MULTIPLICATION, children);
        }

        public static Expression div(Expression numerator, Expression denominator) {
            return new Expression(Operator.DIVISION, Arrays.asList(numerator, denominator));
        }

        public static Expression pow(Expression base, Expression exponent) {
            return new Expression(Operator.POWER, Arrays.asList(base, exponent));
        }

        public static Expression neg(Expression child) {
            return mul(value(-1.0), child);
        }

        public static Expression coef(Double coef, DoubleVar child){
            return mul(value(coef), variable(child));
        }

        public static Expression neg(DoubleVar child) {
            return coef(-1.0, child);
        }

        /**
         * Helper to immediately build, simplify, and lock an expression.
         */
        public static ImmutableExpression buildLocked(Expression rawExpression) {
            rawExpression.simplify();
            return ImmutableExpression.freeze(rawExpression);
        }
    }
}