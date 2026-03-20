package com.minecart.math.function;

import java.util.function.BinaryOperator;
import java.util.function.Predicate;

public class Operator<T> {
    Target target;
    BinaryOperator<T> operator;
    public MultiOperator<T> commutative;

    public Operator(Target target, BinaryOperator<T> operator, MultiOperator<T> commutative){
        this.target = target;
        this.operator = operator;
        this.commutative = commutative;
    }

    public Class<? extends Operator<T>> getBase(){
        return (this instanceof MultiOperator<?> m) ? (Class<? extends Operator<T>>) m.base : (Class<? extends Operator<T>>) this.getClass();
    }

    public enum Target{
        SINGLE(i -> i == 1), DOUBLE(i -> i == 2), MULTIPLE(i -> i > 0);
        Predicate<Integer> targetAmount;
        Target(Predicate<Integer> targetAmount){
            this.targetAmount = targetAmount;
        }
    }

    public static class Addition extends Operator<Double>{
        public Addition() {
            super(Target.DOUBLE, Addition::add, new MultiOperator.Addition());
        }

        public static double add(double a, double b){
            return a + b;
        }
    }

    public static class Subtraction extends Operator<Double> {
        public Subtraction() {
            super(Target.DOUBLE, Subtraction::subtract, null);
        }

        public static double subtract(double a, double b) {
            return a - b;
        }
    }

    public static class Multiplication extends Operator<Double> {
        public Multiplication() {
            super(Target.DOUBLE, Multiplication::multiply, new MultiOperator.Multiplication());
        }

        public static double multiply(double a, double b) {
            return a * b;
        }
    }

    public static class Division extends Operator<Double> {
        public Division() {
            super(Target.DOUBLE, Division::divide, null);
        }

        public static double divide(double a, double b) {
            return a / b;
        }
    }

    /**
     * The exponent must be strictly integer
     */
    public static class Power extends Operator<Double> {
        public Power() {
            super(Target.DOUBLE, Power::power, null);
        }

        public static double power(double a, double b) {
            return Math.pow(a, b);
        }
    }

    public static class Negation extends Operator<Double> {
        public Negation() {
            super(Target.SINGLE, Negation::negate, null);
        }

        // For SINGLE target, the second parameter is ignored during tree evaluation
        public static double negate(double a, double b) {
            return -a;
        }
    }
}