package com.minecart.action;

public class ActionType<T extends Action> {
    protected final String name;

    public ActionType(String id){
        this.name = id;
    }

    public String getName() {
        return name;
    }
}
