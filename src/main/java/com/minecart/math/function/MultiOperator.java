package com.minecart.math.function;

import com.minecart.misc.MultinaryOperator;

public class MultiOperator<T> extends Operator<T> {
    MultinaryOperator<T> multiOperator;
    Class<? extends Operator<T>> base;

    public MultiOperator(Class<? extends Operator<T>> base, MultinaryOperator<T> multiOperator){
        super(Target.MULTIPLE, null, null);
        this.base = base;
        this.multiOperator = multiOperator;
    }

    public static class Addition extends MultiOperator<Double>{
        public Addition() {
            super(Operator.Addition.class, MultiOperator.Addition::add);
        }

        public static Double add(Double... a){
            double sum = 0.0;
            for(double element : a){
                sum += element;
            }
            return sum;
        }
    }

    public static class Multiplication extends MultiOperator<Double> {
        public Multiplication() {
            super(Operator.Multiplication.class, MultiOperator.Multiplication::multiply);
        }

        public static Double multiply(Double... a) {
            if (a.length == 0) return 1.0;
            double product = 1.0;
            for (double element : a) {
                product *= element;
            }
            return product;
        }
    }
}