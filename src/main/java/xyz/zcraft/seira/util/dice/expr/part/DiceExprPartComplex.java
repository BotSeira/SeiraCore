package xyz.zcraft.seira.util.dice.expr.part;

import xyz.zcraft.seira.util.dice.expr.DiceExpr;

import java.util.LinkedList;
import java.util.List;

public class DiceExprPartComplex extends DiceExprPart {
    final LinkedList<DiceExprPart> factors;

    public DiceExprPartComplex(String origString) {
        super(origString.startsWith("-"), origString);

        if (origString.startsWith("-") || origString.startsWith("+")) {
            origString = origString.substring(1);
        }

        final String[] split = origString.split("\\*");

        factors = new LinkedList<>();

        for (String s : split) {
            if (DiceExpr.isDigit(s)) {
                factors.add(new DiceExprPartNumber(s));
            } else {
                factors.add(new DiceExprPartSingle(s));
            }
        }
    }

    @Override
    public List<Integer> calculate() {
        LinkedList<Integer> result = new LinkedList<>();
        for (DiceExprPart factor : factors) {
            result.add(factor.calculate().getFirst());
        }
        return result;
    }
}
