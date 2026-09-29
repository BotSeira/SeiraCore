package xyz.zcraft.seira.util.dice.expr.part;

import java.util.List;

public class DiceExprPartNumber extends DiceExprPart {
    final int num;

    public DiceExprPartNumber(String string) {
        super(Integer.parseInt(string) < 0, string);
        this.num = Integer.parseInt(string);
    }

    @Override
    public List<Integer> calculate() {
        return List.of(num);
    }
}
