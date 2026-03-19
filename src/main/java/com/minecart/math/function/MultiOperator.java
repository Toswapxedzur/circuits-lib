package com.minecart.math.function;

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

    public static class Minimum extends MultiOperator<Double> {
        public Minimum() {
            super(Operator.Minimum.class, MultiOperator.Minimum::min);
        }

        public static Double min(Double... a) {
            if (a.length == 0) return Double.NaN;
            double min = a[0];
            for (int i = 1; i < a.length; i++) {
                min = Math.min(min, a[i]);
            }
            return min;
        }
    }

    public static class Maximum extends MultiOperator<Double> {
        public Maximum() {
            super(Operator.Maximum.class, MultiOperator.Maximum::max);
        }

        public static Double max(Double... a) {
            if (a.length == 0) return Double.NaN;
            double max = a[0];
            for (int i = 1; i < a.length; i++) {
                max = Math.max(max, a[i]);
            }
            return max;
        }
    }
}