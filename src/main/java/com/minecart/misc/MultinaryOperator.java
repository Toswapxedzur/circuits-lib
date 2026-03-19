package com.minecart.misc;

@FunctionalInterface
public interface MultinaryOperator<T> {
    T apply(T[] t);
}