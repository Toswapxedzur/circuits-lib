package com.minecart.action;

import com.minecart.serialization.tag.CompoundTag;

public class Actions {

    public static abstract class SetOneDoubleAction implements Action {

        protected double value;

        public SetOneDoubleAction(double value) {
            this.value = value;
        }

        public SetOneDoubleAction() {
        }

        public double getValue() {
            return value;
        }

        @Override
        public abstract ActionType<? extends Action> getActionType();

        @Override
        public void save(CompoundTag tag) {
            tag.putString(Action.TAG_ACTION_ID, getActionType().getId());
            tag.putDouble("double_value", value);
        }

        @Override
        public void load(CompoundTag tag) {
            this.value = tag.getDouble("double_value");
        }
    }

    public static abstract class SetOneIntAction implements Action {

        protected int value;

        public SetOneIntAction(int value) {
            this.value = value;
        }

        public SetOneIntAction() {
        }

        public int getValue() {
            return value;
        }

        @Override
        public abstract ActionType<? extends Action> getActionType();

        @Override
        public void save(CompoundTag tag) {
            tag.putString(Action.TAG_ACTION_ID, getActionType().getId());
            tag.putInt("int_value", value);
        }

        @Override
        public void load(CompoundTag tag) {
            this.value = tag.getInt("int_value");
        }
    }

    public static class SetVoltageAction extends SetOneDoubleAction {
        public SetVoltageAction(double value) {
            super(value);
        }

        public SetVoltageAction() {
        }

        @Override
        public ActionType<SetVoltageAction> getActionType() {
            return ActionTypes.SET_VOLTAGE;
        }
    }

    public static class SetResistanceAction extends SetOneDoubleAction {
        public SetResistanceAction(double value) {
            super(value);
        }

        public SetResistanceAction() {
        }

        @Override
        public ActionType<SetResistanceAction> getActionType() {
            return ActionTypes.SET_RESISTANCE;
        }
    }

    public static class SetConnectionAction extends SetOneIntAction {
        public SetConnectionAction(int value) {
            super(value);
        }

        public SetConnectionAction() {
        }

        @Override
        public ActionType<SetConnectionAction> getActionType() {
            return ActionTypes.SET_CONNECTION;
        }
    }

    public static class SetCapacitanceAction extends SetOneDoubleAction {
        public SetCapacitanceAction(double value) {
            super(value);
        }

        public SetCapacitanceAction() {
        }

        @Override
        public ActionType<SetCapacitanceAction> getActionType() {
            return ActionTypes.SET_CAPACITANCE;
        }
    }

    public static class SetReverseResistanceAction extends SetOneDoubleAction {
        public SetReverseResistanceAction(double value) {
            super(value);
        }

        public SetReverseResistanceAction() {
        }

        @Override
        public ActionType<SetReverseResistanceAction> getActionType() {
            return ActionTypes.SET_REVERSE_RESISTANCE;
        }
    }
}
