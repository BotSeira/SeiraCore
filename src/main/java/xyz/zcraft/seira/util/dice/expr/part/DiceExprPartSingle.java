package xyz.zcraft.seira.util.dice.expr.part;

import java.util.List;
import java.util.Random;

public class DiceExprPartSingle extends DiceExprPart {
    final int number;
    final int faces;

    public DiceExprPartSingle(String str) {
        super(str.startsWith("-"), str);
        if (str.startsWith("-") || str.startsWith("+"))
            str = str.substring(1);

        final int split = str.indexOf("d");
        this.number = Integer.parseInt(str.substring(0, split));
        this.faces = Integer.parseInt(str.substring(split + 1));
    }

    @Override
    public List<Integer> calculate() {
        int result = 0;
        for (int i = 0; i < number; i++) {
            result += new Random().nextInt(faces) + 1;
        }

        if (negative) result = -result;

        return List.of(result);
    }
}
