package com.minecart.math.function;

import java.util.function.BinaryOperator;

public class DoubleOperator<T> extends Operator<T>{
    BinaryOperator<T> operator;

    protected DoubleOperator(char symbol, BinaryOperator<T> operator){
        super(symbol, Target.DOUBLE);
        this.operator = operator;
    }

    public static class Division extends DoubleOperator<Double> {
        protected Division() {
            super('/', Division::divide);
        }

        public static double divide(double a, double b) {
            return a / b;
        }
    }

    public static class Power extends DoubleOperator<Double> {
        protected Power() {
            super('^', Power::power);
        }

        public static double power(double a, double b) {
            return Math.pow(a, b);
        }
    }
}