package com.minecart.action;

import java.util.function.DoubleUnaryOperator;
import java.util.function.IntUnaryOperator;

public class Actions {
    public static abstract class SetOneDoubleAction implements Action {
        protected final DoubleUnaryOperator operator;

        public SetOneDoubleAction(DoubleUnaryOperator operator){
            this.operator = operator;
        }

        public SetOneDoubleAction(double value){
            this.operator = x -> value;
        }

        public DoubleUnaryOperator getOperator() {
            return operator;
        }
    }

    public static abstract class SetOneIntAction implements Action {
        protected final IntUnaryOperator operator;

        public SetOneIntAction(IntUnaryOperator operator){
            this.operator = operator;
        }

        public SetOneIntAction(int value){
            this.operator = x -> value;
        }

        public IntUnaryOperator getOperator() {
            return operator;
        }
    }

    public static class SetVoltageAction extends SetOneDoubleAction {
        public SetVoltageAction(DoubleUnaryOperator operator) {
            super(operator);
        }

        public SetVoltageAction(double value) {
            super(value);
        }
    }

    public static class SetResistanceAction extends SetOneDoubleAction {
        public SetResistanceAction(DoubleUnaryOperator operator) {
            super(operator);
        }

        public SetResistanceAction(double value) {
            super(value);
        }
    }

    public static class SetConnectionAction extends SetOneIntAction{
        public SetConnectionAction(IntUnaryOperator operator) {
            super(operator);
        }

        public SetConnectionAction(int value) {
            super(value);
        }
    }

    public static class SetCapacitanceAction extends SetOneDoubleAction {
        public SetCapacitanceAction(DoubleUnaryOperator operator) {
            super(operator);
        }

        public SetCapacitanceAction(double value) {
            super(value);
        }
    }
}
