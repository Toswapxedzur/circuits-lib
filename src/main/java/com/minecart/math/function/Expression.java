package com.minecart.math.function;

import org.apache.commons.math3.util.Pair;

import java.util.*;

public class Expression {
    protected Expression[] children;
    protected Operator<Double> operator;
    protected Variable<Double> variable;
    protected Double constant;
    protected boolean leaf;

    public Expression(Operator<Double> operator, Expression... children) {
        this.operator = operator;
        this.children = children;
        this.variable = null;
        this.constant = null;
        this.leaf = false;

        if (operator.target.targetAmount.test(children.length)) {
            throw new IllegalArgumentException("Expression amount incorrect");
        }
    }

    public Expression(Variable variable) {
        this.operator = null;
        this.children = new Expression[0];
        this.variable = variable;
        this.constant = null;
        this.leaf = true;
    }

    public Expression(double constant) {
        this.operator = null;
        this.children = new Expression[0];
        this.variable = null;
        this.constant = constant;
        this.leaf = true;
    }

    /**
     * Recursively traverses the AST to collect all unique variables used in this expression.
     */
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
            // A - B  ->  A + (-1.0 * B)
            this.operator = new Operator.Addition();
            Expression negOne = new Expression(-1.0);
            this.children[1] = new Expression(new Operator.Multiplication(), negOne, this.children[1]);
            this.children[1].simplify();

        } else if (operator instanceof Operator.Division) {
            // A / B  ->  A * (B ^ -1.0)
            this.operator = new Operator.Multiplication();
            this.children[1] = new Expression(new Operator.Power(), this.children[1], new Expression(-1.0));
            this.children[1].simplify();

        } else if (operator instanceof Operator.Negation) {
            // -A  ->  -1.0 * A
            // We upgrade the operator to Multiplication and insert a constant -1.0 as the left child
            this.operator = new Operator.Multiplication();
            Expression negOne = new Expression(-1.0);
            this.children = new Expression[] { negOne, this.children[0] };
            // No need to call simplify on the children here, as the bottom-up
            // traversal guarantees children[0] is already simplified.
        }
    }

    private void flattenAssociative() {
        // Automatically upgrade binary operators to their MultiOperator forms
        if (operator.commutative != null) {
            this.operator = operator.commutative;
        }

        // Dynamically flatten children that share the same base mathematical property
        if (operator instanceof MultiOperator<?> multiOp) {
            java.util.List<Expression> flatChildren = new java.util.ArrayList<>();
            for (Expression child : children) {
                if (!child.leaf && (child.operator.getClass() == multiOp.base || child.operator.getClass() == multiOp.getClass())) {
                    flatChildren.addAll(java.util.Arrays.asList(child.children));
                } else {
                    flatChildren.add(child);
                }
            }
            this.children = flatChildren.toArray(new Expression[0]);
        }
    }

    private void foldConstants() {
        List<Expression> consts = new ArrayList<>();
        List<Expression> vars = new ArrayList<>();

        for (Expression child : children) {
            if (child.leaf && child.constant != null) consts.add(child);
            else vars.add(child);
        }

        // If no constants exist, or only one exists in a multi-operation, do nothing
        if (consts.isEmpty() || (consts.size() == 1 && !vars.isEmpty())) return;

        // Evaluate the grouped constants abstractly
        Double[] args = consts.stream().map(c -> c.constant).toArray(Double[]::new);
        double foldedValue = 0.0;

        if (operator.target == Operator.Target.SINGLE) {
            foldedValue = operator.operator.apply(args[0], 0.0);
        } else if (operator instanceof MultiOperator<Double> multiOp) {
            foldedValue = multiOp.multiOperator.apply(args); // Raw type cast for abstraction
        } else {
            foldedValue = operator.operator.apply(args[0], args[1]);
        }

        // Catch arithmetic errors natively
        if (Double.isInfinite(foldedValue) || Double.isNaN(foldedValue)) {
            throw new ArithmeticException("Mathematical error during constant folding such as Division by Zero");
        }

        if (vars.isEmpty()) {
            transformToLeaf(foldedValue); // The entire branch becomes a single number
        } else {
            vars.add(new Expression(foldedValue)); // Append the folded number back to the variables
            this.children = vars.toArray(new Expression[0]);
        }
    }

    private void applyIdentities() {
        if (leaf) return;
        Class<?> baseOp = (operator instanceof MultiOperator<?> m) ? m.base : operator.getClass();

        if (baseOp == Operator.Multiplication.class) {
            if (hasConstant(0.0)) transformToLeaf(0.0);         // Annihilation: X * 0 = 0
            else removeConstants(1.0);                          // Identity: X * 1 = X
        } else if (baseOp == Operator.Addition.class) {
            removeConstants(0.0);                               // Identity: X + 0 = X
        } else if (baseOp == Operator.Power.class) {
            if (isConstant(children[1], 0.0)) transformToLeaf(1.0);      // X ^ 0 = 1
            else if (isConstant(children[1], 1.0)) replaceWith(children[0]); // X ^ 1 = X
            else if (isConstant(children[0], 0.0)) transformToLeaf(0.0);     // 0 ^ X = 0
        }

        // Single-child cleanup: If an Addition/Multiplication only has 1 child left, dissolve the operator
        if (!leaf && children.length == 1 && operator.target == Operator.Target.MULTIPLE) {
            replaceWith(children[0]);
        }
    }

    private void expandTerms() {
        if (leaf || children.length < 2) return;
        Class<?> baseOp = operator.getBase();

        // Expand: A * (B + C) -> A*B + A*C
        if (baseOp == Operator.Multiplication.class) {
            Expression sumNode = null;
            List<Expression> multipliers = new ArrayList<>();

            // Find the first Addition node to distribute across
            for (Expression child : children) {
                if (sumNode == null && !child.leaf && (child.operator instanceof Operator.Addition || child.operator instanceof MultiOperator.Addition)) {
                    sumNode = child;
                } else {
                    multipliers.add(child);
                }
            }

            if (sumNode != null) {
                Expression currentMultipliers = new Expression(new MultiOperator.Multiplication(), multipliers.toArray(new Expression[0]));
                Expression[] distributedTerms = new Expression[sumNode.children.length];

                for (int i = 0; i < sumNode.children.length; i++) {
                    distributedTerms[i] = new Expression(new MultiOperator.Multiplication(), currentMultipliers, sumNode.children[i]);
                    distributedTerms[i].simplify(); // Simplify the newly created term
                }

                this.operator = new MultiOperator.Addition();
                this.children = distributedTerms;
            }
        }
    }

    private void transformToLeaf(double val) {
        this.operator = null;
        this.children = new Expression[0];
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
        return java.util.Arrays.stream(children).anyMatch(c -> isConstant(c, targetValue));
    }

    private boolean isConstant(Expression expr, double targetValue) {
        return expr.leaf && expr.constant != null && expr.constant == targetValue;
    }

    private void removeConstants(double targetValue) {
        this.children = java.util.Arrays.stream(children)
                .filter(c -> !isConstant(c, targetValue))
                .toArray(Expression[]::new);
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
        List<Double> intercept = List.of();

        extractLinearTerms(termMap, intercept);

        List<Pair<Double, Variable<Double>>> terms = new ArrayList<>();
        for (Map.Entry<Variable<Double>, Double> entry : termMap.entrySet()) {
            if (entry.getValue() != 0.0) { // Clean up any variables that algebraically canceled out to 0
                terms.add(new Pair<>(entry.getValue(), entry.getKey()));
            }
        }

        double sum = 0.0;
        for (Double t : intercept)
            sum+=t;
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
}