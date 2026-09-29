package xyz.zcraft.seira.util.dice.result;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public class DiceResult {
    private final List<List<Integer>> parts;

    public DiceResult(List<List<Integer>> parts) {
        this.parts = parts;
    }

    public DiceResult(int num) {
        this.parts = List.of(List.of(num));
    }

    public int total() {
        int sum = 0;
        for (var factors : parts) {
            int tmp = 1;
            for (var factor : factors) {
                tmp *= factor;
            }
            sum += tmp;
        }

        return sum;
    }

    @NotNull
    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if(i == 0) {
                if (parts.get(i).size() < 2) {
                    sb.append(parts.get(i).getFirst());
                    continue;
                }
            } else {
                if (parts.get(i).size() >= 2) sb.append("+");
                else {
                    if(parts.get(i).getFirst() >= 0) {
                        sb.append("+");
                    }
                    sb.append(parts.get(i).getFirst());
                    continue;
                }
            }

            for (int j = 0; j < parts.get(i).size(); j++) {
                final int cur = parts.get(i).get(j);
                if (j != 0) {
                    sb.append("*");
                }
                if (cur >= 0) sb.append(cur);
                else sb.append("(-").append(cur).append(")");
            }
        }

        return sb.toString();
    }
}
