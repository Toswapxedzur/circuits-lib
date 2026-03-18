package com.minecart.math.function;

import java.util.function.BinaryOperator;
import java.util.function.Predicate;

public class Operator<T> {
    Target target;
    BinaryOperator<T> operator;

    public Operator(Target target, BinaryOperator<T> operator){
        this.target = target;
        this.operator = operator;
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
            super(Target.DOUBLE, Addition::add);
        }

        public static double add(double a, double b){
            return a + b;
        }
    }

    public static class MultiAddition extends Operator<Double>{
        public MultiAddition() {
            super(Target.MULTIPLE, Addition::add);
        }

        public static double add(double a, double b){
            return a + b;
        }
    }

    public static class Subtraction extends Operator<Double> {
        public Subtraction() {
            super(Target.DOUBLE, Subtraction::subtract);
        }

        public static double subtract(double a, double b) {
            return a - b;
        }
    }

    public static class Multiplication extends Operator<Double> {
        public Multiplication() {
            super(Target.DOUBLE, Multiplication::multiply);
        }

        public static double multiply(double a, double b) {
            return a * b;
        }
    }

    public static class Division extends Operator<Double> {
        public Division() {
            super(Target.DOUBLE, Division::divide);
        }

        public static double divide(double a, double b) {
            return a / b;
        }
    }

    public static class Power extends Operator<Double> {
        public Power() {
            super(Target.DOUBLE, Power::power);
        }

        public static double power(double a, double b) {
            return Math.pow(a, b);
        }
    }

    public static class Logarithm extends Operator<Double> {
        public Logarithm() {
            super(Target.DOUBLE, Logarithm::log);
        }

        public static double log(double a, double b) {
            return Math.log(a) / Math.log(b);
        }
    }

    public static class Negation extends Operator<Double> {
        public Negation() {
            super(Target.SINGLE, Negation::negate);
        }

        // For SINGLE target, the second parameter is ignored during tree evaluation
        public static double negate(double a, double b) {
            return -a;
        }
    }

    public static class Sine extends Operator<Double> {
        public Sine() {
            super(Target.SINGLE, Sine::sin);
        }

        public static double sin(double a, double b) {
            return Math.sin(a);
        }
    }

    public static class Cosine extends Operator<Double> {
        public Cosine() {
            super(Target.SINGLE, Cosine::cos);
        }

        public static double cos(double a, double b) {
            return Math.cos(a);
        }
    }

    public static class Modulo extends Operator<Double> {
        public Modulo() {
            super(Target.DOUBLE, Modulo::modulo);
        }

        public static double modulo(double a, double b) {
            return a % b;
        }
    }

    public static class Minimum extends Operator<Double> {
        public Minimum() {
            super(Target.DOUBLE, Minimum::min);
        }

        public static double min(double a, double b) {
            return Math.min(a, b);
        }
    }

    public static class Maximum extends Operator<Double> {
        public Maximum() {
            super(Target.DOUBLE, Maximum::max);
        }

        public static double max(double a, double b) {
            return Math.max(a, b);
        }
    }

    public static class Tangent extends Operator<Double> {
        public Tangent() {
            super(Target.SINGLE, Tangent::tan);
        }

        public static double tan(double a, double b) {
            return Math.tan(a);
        }
    }

    public static class Absolute extends Operator<Double> {
        public Absolute() {
            super(Target.SINGLE, Absolute::abs);
        }

        public static double abs(double a, double b) {
            return Math.abs(a);
        }
    }
}