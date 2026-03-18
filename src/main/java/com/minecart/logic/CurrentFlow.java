package com.minecart.logic;

public enum CurrentFlow {
    IN(1), NO(0), OUT(-1);

    int sign;
    CurrentFlow(int sign){
        this.sign = sign;
    }
}
