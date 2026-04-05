package com.minecart.math;

@Deprecated
public class MultiOperator extends Operator{
    MultiDoubleOperator multiOperator;

    protected MultiOperator(char symbol, MultiDoubleOperator multiOperator){
        super(symbol, Target.MULTIPLE);
        this.multiOperator = multiOperator;
    }

    public static class Addition extends MultiOperator {
        protected Addition() {
            super('+', MultiOperator.Addition::add);
        }

        public static double add(double... a){
            double sum = 0.0;
            for(double element : a){
                sum += element;
            }
            return sum;
        }
    }

    public static class Multiplication extends MultiOperator {
        protected Multiplication() {
            super('*', MultiOperator.Multiplication::multiply);
        }

        public static double multiply(double... a) {
            if (a.length == 0) return 1.0;
            double product = 1.0;
            for (double element : a) {
                product *= element;
            }
            return product;
        }
    }
}