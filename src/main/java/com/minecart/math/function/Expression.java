package com.minecart.math.function;

import java.util.Set;

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
    public void collectVar(Set<Variable> set) {
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
}