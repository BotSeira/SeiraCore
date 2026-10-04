package xyz.zcraft.seira.addpp;

import xyz.zcraft.seira.command.parse.TargetInput;

import java.util.Arrays;
import java.util.List;
import java.util.function.ToLongFunction;

/**
 * Separates the optional player from the score conditions.
 */
public record AddPpRequest(AddPpQuery query, String player, TargetInput shortcut) {
    public AddPpRequest(AddPpQuery query, String player) {
        this(query, player, null);
    }

    public static AddPpRequest parse(String[] args) {
        if (args != null && args.length > 0
                && (isShortcut(args[0]) || (args.length > 1 && isShortcut(args[1])))) {
            var target = TargetInput.read(args);
            var conditions = List.copyOf(Arrays.asList(args).subList(target.consumedArgs(), args.length));
            if (conditions.size() > 20) throw new IllegalArgumentException("成绩条件过多喵。");
            // The map ID is resolved before this pending query is sent to oStella.
            return new AddPpRequest(new AddPpQuery(null, 1, null, conditions), target.player(), target);
        }
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

    private static boolean isShortcut(String value) {
        return value.matches("(?i)(rs|rp|bp)\\d*");
    }

    public AddPpQuery resolveQuery(ToLongFunction<TargetInput> beatmapLookup) {
        if (shortcut == null) return query;
        long beatmapId = beatmapLookup.applyAsLong(shortcut);
        if (beatmapId <= 0) throw new IllegalArgumentException("快捷查询未返回有效谱面喵。");
        return new AddPpQuery(null, 1, beatmapId, query.conditions());
    }
}
