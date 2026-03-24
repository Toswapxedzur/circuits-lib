package com.minecart.math.function;

import java.util.function.DoubleBinaryOperator;

public class DoubleOperator extends Operator{
    DoubleBinaryOperator operator;

    protected DoubleOperator(char symbol, DoubleBinaryOperator operator){
        super(symbol, Target.DOUBLE);
        this.operator = operator;
    }

    public static class Division extends DoubleOperator {
        protected Division() {
            super('/', Division::divide);
        }

        public static double divide(double a, double b) {
            return a / b;
        }
    }

    public static class Power extends DoubleOperator {
        protected Power() {
            super('^', Power::power);
        }

        public static double power(double a, double b) {
            return Math.pow(a, b);
        }
    }
}