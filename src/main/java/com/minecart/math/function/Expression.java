package com.minecart.math.function;

import org.apache.commons.math3.util.Pair;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Could also be used to represent an equation with exp = 0
 */
public class Expression {
    protected List<Expression> children;
    protected Operator<Double> operator;
    protected Variable<Double> variable;
    protected Double constant;
    protected boolean leaf;

    public Expression(Operator<Double> operator, List<Expression> children) {
        this.operator = operator;
        // Wrapping in ArrayList ensures the internal list is always mutable
        this.children = new ArrayList<>(children);
        this.variable = null;
        this.constant = null;
        this.leaf = false;

        if (operator.target.targetAmount.test(children.size())) {
            throw new IllegalArgumentException("Expression amount incorrect");
        }
    }

    public Expression(Variable<Double> variable) {
        this.operator = null;
        this.children = new ArrayList<>();
        this.variable = variable;
        this.constant = null;
        this.leaf = true;
    }

    public Expression(double constant) {
        this.operator = null;
        this.children = new ArrayList<>();
        this.variable = null;
        this.constant = constant;
        this.leaf = true;
    }

    public void collectVar(Set<Variable<Double>> set) {
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
        normalize();
        flattenAssociative();
        foldConstants();
        applyIdentities();
        expandTerms();
    }

    private void normalize() {
        if (operator instanceof Operator.Subtraction) {
            this.operator = new Operator.Addition();
            Expression negOne = new Expression(-1.0);
            Expression newRight = new Expression(new Operator.Multiplication(), Arrays.asList(negOne, this.children.get(1)));
            newRight.simplify();
            this.children = new ArrayList<>(Arrays.asList(this.children.get(0), newRight));

        } else if (operator instanceof Operator.Division) {
            this.operator = new Operator.Multiplication();
            Expression newRight = new Expression(new Operator.Power(), Arrays.asList(this.children.get(1), new Expression(-1.0)));
            newRight.simplify();
            this.children = new ArrayList<>(Arrays.asList(this.children.get(0), newRight));

        } else if (operator instanceof Operator.Negation) {
            this.operator = new Operator.Multiplication();
            Expression negOne = new Expression(-1.0);
            this.children = new ArrayList<>(Arrays.asList(negOne, this.children.get(0)));
        }
    }

    private void flattenAssociative() {
        if (operator.commutative != null) {
            this.operator = operator.commutative;
        }

        // Dynamically flatten children that share the same base mathematical property
        if (operator instanceof MultiOperator<?> multiOp) {
            List<Expression> flatChildren = new ArrayList<>();
            for (Expression child : children) {
                if (!child.leaf && (child.operator.getClass() == multiOp.base || child.operator.getClass() == multiOp.getClass())) {
                    flatChildren.addAll(child.children); // Much cleaner without Arrays.asList()
                } else {
                    flatChildren.add(child);
                }
            }
            this.children = flatChildren;
        }
    }

    private void foldConstants() {
        List<Expression> consts = new ArrayList<>();
        List<Expression> vars = new ArrayList<>();

        for (Expression child : children) {
            if (child.leaf && child.constant != null) consts.add(child);
            else vars.add(child);
        }

        if (consts.isEmpty() || (consts.size() == 1 && !vars.isEmpty())) return;

        Double[] args = consts.stream().map(c -> c.constant).toArray(Double[]::new);
        double foldedValue = 0.0;

        if (operator.target == Operator.Target.SINGLE) {
            foldedValue = operator.operator.apply(args[0], 0.0);
        } else if (operator instanceof MultiOperator<Double> multiOp) {
            foldedValue = multiOp.multiOperator.apply(args);
        } else {
            foldedValue = operator.operator.apply(args[0], args[1]);
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

    private void applyIdentities() {
        if (leaf) return;
        Class<?> baseOp = (operator instanceof MultiOperator<?> m) ? m.base : operator.getClass();

        if (baseOp == Operator.Multiplication.class) {
            if (hasConstant(0.0)) transformToLeaf(0.0);
            else removeConstants(1.0);
        } else if (baseOp == Operator.Addition.class) {
            removeConstants(0.0);
        } else if (baseOp == Operator.Power.class) {
            if (isConstant(children.get(1), 0.0)) transformToLeaf(1.0);
            else if (isConstant(children.get(1), 1.0)) replaceWith(children.get(0));
            else if (isConstant(children.get(0), 0.0)) transformToLeaf(0.0);
        }

        if (!leaf && children.size() == 1 && operator.target == Operator.Target.MULTIPLE) {
            replaceWith(children.get(0));
        }
    }

    private void expandTerms() {
        if (leaf || children.size() < 2) return;
        Class<?> baseOp = operator.getBase();

        if (baseOp == Operator.Multiplication.class) {
            Expression sumNode = null;
            List<Expression> multipliers = new ArrayList<>();

            for (Expression child : children) {
                if (sumNode == null && !child.leaf && (child.operator instanceof Operator.Addition || child.operator instanceof MultiOperator.Addition)) {
                    sumNode = child;
                } else {
                    multipliers.add(child);
                }
            }

            if (sumNode != null) {
                Expression currentMultipliers = new Expression(new MultiOperator.Multiplication(), multipliers);
                List<Expression> distributedTerms = new ArrayList<>();

                for (int i = 0; i < sumNode.children.size(); i++) {
                    Expression newTerm = new Expression(new MultiOperator.Multiplication(), Arrays.asList(currentMultipliers, sumNode.children.get(i)));
                    newTerm.simplify();
                    distributedTerms.add(newTerm);
                }

                this.operator = new MultiOperator.Addition();
                this.children = distributedTerms;
            }
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
        return expr.leaf && expr.constant != null && expr.constant == targetValue;
    }

    private void removeConstants(double targetValue) {
        this.children = children.stream()
                .filter(c -> !isConstant(c, targetValue))
                .collect(Collectors.toList());
    }

    public boolean isLinear() {
        if (leaf) return true;

        Class<?> baseOp = operator.getBase();

        if (baseOp == Operator.Addition.class) {
            for (Expression child : children) {
                if (!child.isLinear())
                    return false;
            }
            return true;
        } else if (baseOp == Operator.Multiplication.class) {
            int varCount = 0;
            for (Expression child : children) {
                if (child.leaf && child.constant != null) {
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

    public Pair<List<Pair<Double, Variable<Double>>>, Double> toLinear() {
        Map<Variable<Double>, Double> termMap = new HashMap<>();
        List<Double> intercept = new ArrayList<>(); // Fixed: Was List.of() which is immutable

        extractLinearTerms(termMap, intercept);

        List<Pair<Double, Variable<Double>>> terms = new ArrayList<>();
        for (Map.Entry<Variable<Double>, Double> entry : termMap.entrySet()) {
            if (entry.getValue() != 0.0) {
                terms.add(new Pair<>(entry.getValue(), entry.getKey()));
            }
        }

        double sum = 0.0;
        for (Double t : intercept)
            sum += t;

        return new Pair<>(terms, sum);
    }

    private void extractLinearTerms(Map<Variable<Double>, Double> termMap, List<Double> intercept) {
        if (leaf) {
            if (constant != null) {
                intercept.add(constant);
            } else if (variable != null) {
                termMap.merge(variable, 1.0, Double::sum);
            }
            return;
        }

        Class<?> baseOp = operator.getBase();
        if (baseOp == Operator.Addition.class) {
            for (Expression child : children) {
                child.extractLinearTerms(termMap, intercept);
            }
        } else if (baseOp == Operator.Multiplication.class) {
            double coeff = 1.0;
            Variable<Double> var = null;

            for (Expression child : children) {
                if (child.leaf && child.constant != null)
                    coeff *= child.constant;
                else if (child.leaf && child.variable != null)
                    var = child.variable;
            }

            if (var != null) {
                termMap.merge(var, coeff, Double::sum);
            } else {
                intercept.add(coeff);
            }
        }
    }

    public static class ImmutableExpression extends Expression{
        public ImmutableExpression(Operator<Double> operator, List<Expression> children) {
            super(operator, children);
            this.children = Collections.unmodifiableList(this.children);
        }

        public ImmutableExpression(Variable<Double> variable) {
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
                if (expr.constant != null) {
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

        public static Expression var(Variable<Double> variable) {
            return new Expression(variable);
        }

        public static Expression val(double constant) {
            return new Expression(constant);
        }

        public static Expression add(Expression... children) {
            return new Expression(new MultiOperator.Addition(), Arrays.asList(children));
        }

        public static Expression mul(Expression... children) {
            return new Expression(new MultiOperator.Multiplication(), Arrays.asList(children));
        }

        public static Expression min(Expression... children) {
            return new Expression(new MultiOperator.Minimum(), Arrays.asList(children));
        }

        public static Expression max(Expression... children) {
            return new Expression(new MultiOperator.Maximum(), Arrays.asList(children));
        }

        public static Expression sub(Expression left, Expression right) {
            return new Expression(new Operator.Subtraction(), Arrays.asList(left, right));
        }

        public static Expression div(Expression numerator, Expression denominator) {
            return new Expression(new Operator.Division(), Arrays.asList(numerator, denominator));
        }

        public static Expression pow(Expression base, Expression exponent) {
            return new Expression(new Operator.Power(), Arrays.asList(base, exponent));
        }

        public static Expression neg(Expression child) {
            return new Expression(new Operator.Negation(), Arrays.asList(child));
        }

        public static Expression abs(Expression child) {
            return new Expression(new Operator.Absolute(), Arrays.asList(child));
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