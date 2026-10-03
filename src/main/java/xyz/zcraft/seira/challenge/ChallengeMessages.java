package xyz.zcraft.seira.challenge;

import xyz.zcraft.seira.bot.data.Button;
import xyz.zcraft.seira.bot.data.PendingMessage;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import static xyz.zcraft.seira.challenge.ChallengeModels.*;

public final class ChallengeMessages {
    public static final String RULES = """
            群挑战 · 自选难度规则 v1
            - 每群同时一场；默认 24 小时，可选 1–168 小时，最多 100 人。
            - /gch configure 先配置：指定/随机谱面、固定/不同难度、必选/禁用 Mod、基础星数范围、时长、水平调整；/gch start 开始。
            - /gch join 报名后，按本次配置选择 ranked/approved osu!standard 难度。
            - 水平 = 前 50 BP 中前 20 张合格不同谱面的 Mod 后星数中位数；排除挑战谱面集，至少 5 张。报名后固定，不能更换账号。
            - 开启水平调整时：挑战分 = osu! 标准化分数 × 水平倍率；关闭时直接比较标准化分数。原生 Mod 分数倍率保留。
            - 难度比水平每高 1★加 5%，最多加 10%；每低 1★扣 20%，最多扣 50%。
            - 同水平 ×1.00；高 1★ ×1.05；低 1★ ×0.80。使用 Mod 后实际星数。
            - 只计报名后、截止前通过的在线成绩；辅助/自动 Mod 与自定义 Mod 设置不计入。
            - 支持 NM/NF/EZ/HD/HR/SD/DT/NC/HT/FL/SO/PF/CL/MR（默认设置）。
            - 必选/禁用规则中 NC 等同 DT，PF 等同 SD；NM 要求不加其他 Mod（CL 除外，仍可单独禁用 CL）。
            - 自动收集最近成绩；可用 /gch submit <成绩ID> 补交漏掉的成绩。
            - 每人取最高挑战分；同分按准确率、较早完成时间排序。
            - 发起者或机器人管理员可提前结束；结束后自动公布结果。
            """;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"));

    private ChallengeMessages() {
    }

    public static PendingMessage status(Round round, long now) {
        String state = round.finished() ? "已结束" : now >= round.endsAt() ? "结算中" : "进行中";
        StringBuilder message = new StringBuilder("群挑战 · " + state + "\n" +
                "[" + clean(round.beatmapset().title()) + "](https://osu.ppy.sh/beatmapsets/" + round.beatmapset().id() + ")\n" +
                "截止：`" + TIME.format(Instant.ofEpochMilli(round.endsAt())) + "`\n" +
                "已报名：`" + round.participants().size() + "` 人\n" +
                "可选难度：\n"
        );
        var choices = round.rules().choices(round.beatmapset())
                .stream()
                .filter(map -> round.rules().differentDifficulties()
                        || java.util.Objects.equals(map.id(), round.rules().fixedMapId()))
                .toList();
        choices.stream()
                .limit(12)
                .forEach(map -> message.append("> - ")
                        .append(clean(map.name()))
                        .append(String.format(Locale.ROOT, " %.2f★ (ID %d)", map.stars(), map.id()))
                        .append("\n"));
        if (choices.size() > 12) message.append("更多难度请查看谱面集页面。\n");
        message.append("\n必选 Mod：`")
                .append(ChallengeSettings.displayMods(round.rules().requiredMods()))
                .append("`\n禁用 Mod：`")
                .append(ChallengeSettings.displayMods(round.rules().bannedMods()))
                .append("`\n水平调整：`")
                .append(round.rules().skillAdjustment() ? "开启" : "关闭")
                .append("`\n");

        List<List<Button>> buttons;
        if (!round.finished() && now < round.endsAt()) {
            int id = 1;
            buttons = List.of(
                    List.of(Button.command(id++, "报名", "/gch join")),
                    List.of(Button.openUrl(id++, "打开游戏", "https://direct.seira.top/s/" + round.beatmapset().id())),
                    List.of(
                            Button.command(id++, "查看排行", "/gch lb"),
                            Button.command(id++, "查看规则", "/gch rules")
                    )
            );
        } else {
            buttons = List.of();
        }
        return PendingMessage.ofMarkdownRaw(message.toString(), buttons);
    }

    public static String leaderboard(Round round, int page) {
        var players = round.participants().values().stream().filter(p -> p.best() != null).sorted((a, b) -> {
            int comparison = ChallengeScoring.compare(b.best(), a.best());
            return comparison == 0 ? Long.compare(a.userId(), b.userId()) : comparison;
        }).toList();
        int pages = Math.max(1, (players.size() + 9) / 10);
        if (page < 1 || page > pages) throw new IllegalArgumentException("排行榜页数必须为 1–" + pages + "。");
        StringBuilder message = new StringBuilder(clean(round.beatmapset().title()) + "\n群挑战排行榜 · " + page + "/" + pages + "\n已有成绩：" + players.size() + "/" + round.participants().size() + " 人\n");
        if (players.isEmpty()) return message + "暂时没有符合规则的成绩。";
        for (int i = (page - 1) * 10; i < Math.min(players.size(), page * 10); i++) {
            Participant player = players.get(i);
            Result result = player.best();
            String difficulty = round.beatmapset().maps().stream().filter(map -> map.id() == result.score().beatmapId()).map(MapChoice::name).findFirst().orElse("?");
            String skill = round.rules().skillAdjustment() ? String.format(Locale.ROOT, " / 水平 %.2f★", player.skillStars()) : "";
            message.append(String.format(Locale.ROOT, "%d. [%s](https://osu.ppy.sh/users/%d) · **%,d**\n" + "   %s +%s · %.2f★%s · %.2f%%\n" + "   [%,d × %.3f](https://osu.ppy.sh/scores/%d)\n", i + 1, clean(player.username()), player.userId(), result.points(), clean(difficulty), result.score().mods() == null || result.score().mods().isEmpty() ? "NM" : clean(result.score().mods()), result.score().stars(), skill, result.score().accuracy() * 100, result.score().totalScore(), result.multiplier(), result.score().id()));
        }
        return message.toString();
    }

    public static String clean(String text) {
        return text == null ? "?" : text.replaceAll("[\\[\\]()*`<>\\r\\n]", " ");
    }
}
