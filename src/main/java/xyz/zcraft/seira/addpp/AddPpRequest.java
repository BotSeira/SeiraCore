package xyz.zcraft.seira.addpp;

import java.util.Arrays;

/** Separates the optional player from the score conditions. */
public record AddPpRequest(AddPpQuery query, String player) {
    public static AddPpRequest parse(String[] args) {
        // A single number is PP; spaced multiplication still belongs to the score.
        // A leading map ID keeps all remaining tokens as map conditions.
        if (args == null || args.length < 2 || args[0].matches("(?i)m\\d+"))
            return new AddPpRequest(AddPpQuery.parse(args), null);
        try {
            return new AddPpRequest(AddPpQuery.parse(args), null);
        } catch (IllegalArgumentException ignored) {
            if (args[0].isBlank() || args[0].equals("@"))
                throw new IllegalArgumentException("请提供有效的目标玩家喵。");
            return new AddPpRequest(AddPpQuery.parse(Arrays.copyOfRange(args, 1, args.length)), args[0]);
        }
    }
}
