package xyz.zcraft.seira.addpp;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

public record AddPpQuery(Double pp, Integer count, Long beatmapId, List<String> conditions) {
    private static final Pattern PP = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)(?:pp)?(?:\\s*[*×]\\s*(\\d+))?", Pattern.CASE_INSENSITIVE);

    public AddPpQuery {
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
    }

    public static AddPpQuery parse(String[] args) {
        if (args == null || args.length == 0)
            throw new IllegalArgumentException("请提供单条成绩的 PP，或 m谱面ID 与成绩条件喵。");
        if (args[0].matches("(?i)m\\d+")) {
            long id;
            try {
                id = Long.parseLong(args[0].substring(1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("无法解析谱面 ID 喵。");
            }
            if (id <= 0) throw new IllegalArgumentException("谱面 ID 必须为正整数喵。");
            if (args.length > 21) throw new IllegalArgumentException("成绩条件过多喵。");
            return new AddPpQuery(null, 1, id, Arrays.asList(args).subList(1, args.length));
        }
        var matcher = PP.matcher(String.join(" ", args));
        if (!matcher.matches())
            throw new IllegalArgumentException("无法解析参数。请使用 123、200*4，或 m谱面ID 后接成绩条件喵。");
        try {
            double pp = Double.parseDouble(matcher.group(1));
            int count = matcher.group(2) == null ? 1 : Integer.parseInt(matcher.group(2));
            if (!Double.isFinite(pp) || pp <= 0 || pp > 100_000 || count < 1 || count > 100)
                throw new IllegalArgumentException("单条 PP 必须为 0–100000 之间的正数，条数为 1–100喵。");
            return new AddPpQuery(pp, count, null, List.of());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("PP 或条数过大喵。");
        }
    }
}
