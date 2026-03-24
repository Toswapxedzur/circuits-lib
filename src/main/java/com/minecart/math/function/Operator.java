package com.minecart.math.function;

import java.util.List;
import java.util.function.Predicate;

public class Operator {
    public static final MultiOperator.Addition ADDITION = new MultiOperator.Addition();
    public static final MultiOperator.Multiplication MULTIPLICATION = new MultiOperator.Multiplication();
    public static final DoubleOperator.Division DIVISION = new DoubleOperator.Division();
    public static final DoubleOperator.Power POWER = new DoubleOperator.Power();
    public static final List<Operator> OPERATORS = List.of(ADDITION, MULTIPLICATION, DIVISION, POWER);
    public static final List<MultiOperator> MULTI_OPERATORS = List.of(ADDITION, MULTIPLICATION);
    public static final List<DoubleOperator> DOUBLE_OPERATORS = List.of(DIVISION, POWER);

    public char symbol;
    public Target target;

    protected Operator(char symbol, Target target){
        this.symbol = symbol;
        this.target = target;
    }

    public static Operator parse(char symbol) {
        return OPERATORS.stream().filter(o -> symbol == o.symbol).findAny().orElse(null);
    }

    public enum Target{
        DOUBLE(i -> i == 2), MULTIPLE(i -> i >= 1);
        Predicate<Integer> targetAmount;
        Target(Predicate<Integer> targetAmount){
            this.targetAmount = targetAmount;
        }
    }
}
