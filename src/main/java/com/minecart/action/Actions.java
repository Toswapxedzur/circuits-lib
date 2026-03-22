package com.minecart.action;

import java.util.function.DoubleUnaryOperator;

public class Actions {
    public static abstract class SetOneDoubleAction implements ElectricalAction{
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
}
