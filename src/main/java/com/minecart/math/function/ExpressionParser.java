package com.minecart.math.function;

public class ExpressionParser {
    public static String encrypt(Expression expr) {
        if (expr.leaf) {
            if (expr.constant != null) return expr.constant.toString();
            // Assuming your Variable class overrides toString() to return its name (e.g., "V1")
            return "var";
        }

        Class<?> opClass = expr.operator.getBase();

        // Handle Unary Negation specially
        if (opClass == Operator.Negation.class) {
            return "-(" + encrypt(expr.children.get(0)) + ")";
        }

        char symbol = getOperatorSymbol(opClass);
        StringBuilder sb = new StringBuilder("(");

        for (int i = 0; i < expr.children.size(); i++) {
            sb.append(encrypt(expr.children.get(i)));
            if (i < expr.children.size() - 1) {
                sb.append(" ").append(symbol).append(" ");
            }
        }
        sb.append(")");

        return sb.toString();
    }

    private static char getOperatorSymbol(Class<?> opClass) {
        if (opClass == Operator.Addition.class) return '+';
        if (opClass == Operator.Subtraction.class) return '-';
        if (opClass == Operator.Multiplication.class) return '*';
        if (opClass == Operator.Division.class) return '/';
        if (opClass == Operator.Power.class) return '^';
        throw new IllegalArgumentException("Cannot encrypt non-standard operator: " + opClass.getSimpleName());
    }
}
