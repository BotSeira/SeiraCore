package xyz.zcraft.seira.command.parse;

import xyz.zcraft.seira.command.ResolutionException;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

public record TargetInput(Kind kind, String id, long index, String player, int consumedArgs) {
    private static final Pattern PLAYER_SCORE = Pattern.compile("(?i)^(rs|rp|bp)(\\d+)?$");
    private static final Pattern SET = Pattern.compile("^(\\d+)#(\\d+)$");

    public static TargetInput memory() {
        return new TargetInput(Kind.MEMORY, null, 1, null, 0);
    }

    public static TargetInput read(String[] args) {
        return read(args, false);
    }

    /**
     * Score commands can select a player's score on an explicit beatmap.
     */
    public static TargetInput readScoreTarget(String[] args) {
        return read(args, true);
    }

    private static TargetInput read(String[] args, boolean scoreTarget) {
        if (args.length == 0) return memory();
        int consumed = args.length >= 2 && (PLAYER_SCORE.matcher(args[1]).matches()
                || (scoreTarget && args[1].matches("(?i)(?:m\\d+|mp\\d*)"))) ? 2 : 1;
        String player = consumed == 2 ? args[0] : null;
        String value = args[consumed - 1].trim().toLowerCase(Locale.ROOT);
        if (value.equals("rbp")) {
            value = "bp" + (ThreadLocalRandom.current().nextInt(200) + 1);
        }
        var score = PLAYER_SCORE.matcher(value);
        if (score.matches()) {
            long index = score.group(2) == null ? 1 : positive(score.group(2), "快捷指令索引无效，请输入 1-200 之间的数字。例如: rp5");
            if (index > 200) throw new ResolutionException("快捷指令索引无效，请输入 1-200 之间的数字。例如: rp5");
            return new TargetInput(Kind.valueOf(score.group(1).toUpperCase(Locale.ROOT)), null, index, player, consumed);
        }
        var set = SET.matcher(value);
        if (set.matches()) {
            long id = positive(set.group(1), "谱面集索引无效。例如: 12345#2");
            long index = positive(set.group(2), "谱面集索引无效。例如: 12345#2");
            return new TargetInput(Kind.SET, Long.toString(id), index, null, consumed);
        }
        if (value.equals("mp")) return new TargetInput(Kind.MP, null, 1, player, consumed);
        if (value.startsWith("mp")) {
            String room = value.substring(2);
            if (!room.matches("[0-9]+")) throw new ResolutionException("多人房间ID无效，请使用 mp房间ID，例如 mp123456。");
            return new TargetInput(Kind.MP, Long.toString(positive(room, "多人房间ID必须为正整数。")), 1, player, consumed);
        }
        if (value.matches("loc[1-9]\\d*")) return new TargetInput(Kind.SCORE, value, 1, null, consumed);
        if (value.matches("m\\d+")) {
            long id = positive(value.substring(1), "谱面ID无效");
            return new TargetInput(Kind.MAP, Long.toString(id), 1, player, consumed);
        }
        long id = positive(value, "参数无效。请输入数字ID、本地成绩ID或快捷指令 (例如 loc123456789, rp1, 12345#2)。");
        return new TargetInput(Kind.ID, Long.toString(id), 1, null, consumed);
    }

    private static long positive(String value, String message) {
        try {
            long id = Long.parseLong(value);
            if (id > 0) return id;
        } catch (NumberFormatException ignored) {
        }
        throw new ResolutionException(message);
    }

    public String scoreList() {
        return kind.name().toLowerCase(Locale.ROOT);
    }

    public enum Kind {MEMORY, ID, MAP, SET, SCORE, RS, RP, BP, MP}
}
