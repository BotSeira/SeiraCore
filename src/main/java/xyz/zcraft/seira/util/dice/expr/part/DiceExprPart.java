package xyz.zcraft.seira.util.dice.expr.part;

import lombok.Getter;

import java.util.List;

@Getter
public abstract class DiceExprPart {
    protected final boolean negative;
    protected final String origString;

    protected DiceExprPart(boolean negative, String origString) {
        this.negative = negative;
        this.origString = origString;
    }

    public abstract List<Integer> calculate();

    public String toString(boolean signed) {
        if ((origString.startsWith("-") || origString.startsWith("+")) && !signed) {
            return origString.substring(1);
        }
        return origString;
    }
}
