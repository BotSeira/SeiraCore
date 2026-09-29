package xyz.zcraft.seira.util.dice.expr;

import org.jetbrains.annotations.NotNull;
import xyz.zcraft.seira.util.dice.expr.part.DiceExprPart;
import xyz.zcraft.seira.util.dice.expr.part.DiceExprPartComplex;
import xyz.zcraft.seira.util.dice.expr.part.DiceExprPartNumber;
import xyz.zcraft.seira.util.dice.expr.part.DiceExprPartSingle;

import java.util.LinkedList;
import java.util.List;

public record DiceExpr(List<DiceExprPart> parts) {
    public static final DiceExpr CHECK;
    public static final DiceExpr HUNDRED;
    public static final DiceExpr TEN;
    public static final DiceExpr SIX;

    static {
        CHECK = new DiceExpr(List.of(new DiceExprPartSingle("1d100")));
        HUNDRED = new DiceExpr(List.of(new DiceExprPartSingle("1d100")));
        TEN = new DiceExpr(List.of(new DiceExprPartSingle("1d10")));
        SIX = new DiceExpr(List.of(new DiceExprPartSingle("1d6")));
    }

    public static DiceExpr parse(String str) {
        str = str.replace(" ", "");

        if (isInvalid(str)) {
            throw new IllegalArgumentException("骰子表达式有误");
        }

        if (!str.startsWith("-")) str = "+" + str;
        final LinkedList<String> compStr = new LinkedList<>();
        int last = 0;
        for (int i = 1; i < str.length(); i++) {
            if (str.charAt(i) == '+' || str.charAt(i) == '-') {
                compStr.add(str.substring(last, i));
                last = i;
            }
        }

        if (last != str.length() - 1) compStr.add(str.substring(last));

        final LinkedList<DiceExprPart> parts = new LinkedList<>();

        compStr.forEach(s -> {
            if (isDigit(s)) {
                parts.add(new DiceExprPartNumber(s));
            } else if (s.contains("*")) {
                parts.add(new DiceExprPartComplex(s));
            } else {
                parts.add(new DiceExprPartSingle(s));
            }
        });

        return new DiceExpr(parts);
    }

    public static boolean isDigit(String str) {
        if (str == null) return false;
        if (str.startsWith("+") || str.startsWith("-")) str = str.substring(1);
        return str.chars().allMatch(value -> value >= '0' && value <= '9');
    }

    public static boolean isInvalid(String exp) {
        if (exp == null || exp.isBlank()) return true;
        return !exp.chars().allMatch(value -> (value >= '0' && value <= '9') ||
                value == 'd' || value == '+' || value == '*');
    }

    @NotNull
    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        if (parts.getFirst().isNegative()) sb.append("-");
        sb.append(parts.getFirst().toString(false));
        for (int i = 1; i < parts.size(); i++) {
            sb.append(parts.get(i).toString(true));
        }

        return sb.toString();
    }
}
