package com.minecart.math.function;

@FunctionalInterface
public interface MultinaryOperator<T> {
    T apply(T[] t);
}