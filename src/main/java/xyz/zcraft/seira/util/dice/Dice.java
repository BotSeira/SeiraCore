package xyz.zcraft.seira.util.dice;

import xyz.zcraft.seira.util.dice.expr.DiceExpr;
import xyz.zcraft.seira.util.dice.result.DiceResult;

import java.util.LinkedList;
import java.util.List;

public class Dice {
    public DiceResult roll(DiceExpr expression) {
        final List<List<Integer>> results = new LinkedList<>();
        expression.parts().forEach(e -> results.add(e.calculate()));
        return new DiceResult(results);
    }
}
