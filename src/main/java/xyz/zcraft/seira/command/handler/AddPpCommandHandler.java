package xyz.zcraft.seira.command.handler;

import xyz.zcraft.seira.addpp.AddPpApi;
import xyz.zcraft.seira.addpp.AddPpQuery;
import xyz.zcraft.seira.api.OstellaApi;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.command.ResolutionException;
import xyz.zcraft.seira.command.TargetHistory;
import xyz.zcraft.seira.command.parse.Resolver;
import xyz.zcraft.seira.command.parse.TargetInput;
import xyz.zcraft.seira.command.parse.TargetResolver;
import xyz.zcraft.seira.command.reply.CommandUsage;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static xyz.zcraft.seira.command.reply.ReplyFactory.at;

public final class AddPpCommandHandler {
    private static final Pattern PP = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)(?:pp)?(?:\\s*[*×]\\s*(\\d+))?", Pattern.CASE_INSENSITIVE);
    private static final String MAP_TARGET = "(?i)(m\\d+|(?:rs|rp|bp|bd)\\d*)";
    private final AddPpApi api;
    private final Resolver resolver;
    private final TargetHistory history;
    private final TargetResolver targetResolver;

    public AddPpCommandHandler(AddPpApi api, Resolver resolver, TargetResolver targetResolver, TargetHistory history) {
        this.api = api;
        this.resolver = resolver;
        this.targetResolver = targetResolver;
        this.history = history;
    }

    public static String format(AddPpApi.Result result) {
        StringBuilder text = new StringBuilder();
        text.append(String.format(Locale.ROOT, "如果新增成绩：%.2fpp × %d", result.scorePp(), result.count()));
        if (result.map() != null) {
            var map = result.map();
            var hit = map.hits();
            text.append("\n> ").append(escape(map.title())).append(" [").append(escape(map.difficulty())).append("]");
            text.append(String.format(Locale.ROOT, "\n> +%s · %.2f★ · %.4f%% · %dx/%dx\n300/100/50/Miss：%d/%d/%d/%d",
                    map.mods().isEmpty() ? "NM" : map.mods(), map.stars(), hit.accuracy(), hit.combo(), map.maxCombo(),
                    hit.great(), hit.ok(), hit.meh(), hit.misses()));
            if (result.replaced())
                text.append("\n> 同图已有 BP：取更高 PP 替换，不重复计入。");
        } else {
            text.append("\n> (视为不同谱面的新成绩)");
        }

        text.append(String.format(Locale.ROOT, "\n\n总 PP：%.2f → __%.2f__ (+%.2f)", result.beforePp(), result.afterPp(), result.change()));
        if (result.change() < 1e-9) {
            text.append("\n全球排名：").append(rank(result.rank())).append(" → __").append(rank(result.rank())).append("__ (+0)");
        } else {
            var projection = result.rankProjection();
            text.append("\n全球排名：").append(rank(result.rank())).append(" → ");
            if ("COVERED".equals(projection.status())) {
                long next = projection.rank();
                text.append("__#").append(next).append("__");
                if (result.rank() != null && result.rank() > 0)
                    text.append(" (+").append(result.rank() - next).append(")");
            } else if ("HIGH_PP".equals(projection.status())) {
                text.append(projection.rank() == 1 ? "预计 #1" : "预计 #" + projection.rank() + " 或更靠前");
            } else text.append("预计在 #").append(projection.rank()).append(" 之后");
        }
        if (result.positions() != null && !result.positions().isEmpty()) {
            int first = result.positions().getFirst(), last = result.positions().getLast();
            if (first <= 200)
                text.append("\n成绩位置：BP #").append(first).append(first == last ? "" : "–#" + last);
            else text.append("\n成绩未进入前 200 BP喵");
        }

        if (!result.ranked()) {
            text.append("\n> ...但是本成绩不能计入排名喵。");
        }

        return text.toString();
    }

    private static String rank(Long rank) {
        return rank == null || rank <= 0 ? "暂无排名" : "#" + rank;
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("[", "\\[").replace("]", "\\]").replace("*", "\\*").replace("_", "\\_");
    }

    public void handleAddPp(Context ctx) {
        try {
            String[] args = ctx.args();
            if (args.length > 0 && (args[0].matches(MAP_TARGET)
                    || (args.length > 1 && args[1].matches(MAP_TARGET)))) {
                handleMapPp(ctx);
            } else {
                handleRawPp(ctx);
            }
        } catch (IllegalArgumentException | ResolutionException e) {
            ctx.sendReply(at(ctx) + e.getMessage() + "\n> " + CommandUsage.ADDPP);
        }
    }

    private void handleRawPp(Context ctx) {
        String[] args = ctx.args();
        if (args.length == 0) throw new IllegalArgumentException("请提供单条成绩的 PP 喵。");
        var matcher = PP.matcher(String.join(" ", args));
        boolean hasPlayer = args.length > 1 && !matcher.matches();
        String player = hasPlayer ? args[0] : null;
        if (player != null && (player.isBlank() || player.equals("@")))
            throw new IllegalArgumentException("请提供有效的目标玩家喵。");
        if (hasPlayer) matcher = PP.matcher(String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
        if (!matcher.matches())
            throw new IllegalArgumentException("无法解析 PP 参数。请使用 123 或 200*4 喵。");
        double pp;
        int count;
        try {
            pp = Double.parseDouble(matcher.group(1));
            count = matcher.group(2) == null ? 1 : Integer.parseInt(matcher.group(2));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("PP 或条数过大喵。");
        }
        if (!Double.isFinite(pp) || pp <= 0 || pp > 100_000_000 || count < 1 || count > 200)
            throw new IllegalArgumentException("单条 PP 必须为 0–100000000 之间的正数，条数为 1–200喵。");
        var query = new AddPpQuery(pp, count, null, List.of());
        long uid = player == null ? resolver.resolveBoundUid(ctx.senderUserId())
                : OstellaApi.resolveUid(resolver.player(player, ctx.senderUserId()));
        if (uid <= 0) {
            ctx.sendReply(at(ctx) + CommandUsage.NO_BIND);
            return;
        }
        var result = api.estimate(uid, query);
        ctx.sendReply(at(ctx) + (player == null ? "" : "目标玩家：" + uid + "\n") + format(result));
    }

    private void handleMapPp(Context ctx) {
        String[] args = ctx.args();

        long uid = resolver.requireBind(ctx);

        TargetInput target;
        if (resolver.looksLikeMention(args[0]) || TargetInput.looksLikeScoreRef(args[0])) {
            target = TargetInput.read(args);
        } else {
            target = TargetInput.memory();
        }

        final TargetHistory.Ids ids = targetResolver.beatmap(ctx, target, history.get(ctx));
        final Long beatmapId = ids.beatmapId();
        if (beatmapId == null || beatmapId <= 0) throw new IllegalArgumentException("未找到谱面喵。");
        history.remember(ctx, ids);

        var query = new AddPpQuery(null, 1, beatmapId, List.of(args).subList(target.consumedArgs(), args.length));
        var result = api.estimate(uid, query);

        ctx.sendReply(at(ctx) + format(result));
    }
}
