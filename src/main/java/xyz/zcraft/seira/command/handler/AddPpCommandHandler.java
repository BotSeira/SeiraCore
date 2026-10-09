package xyz.zcraft.seira.command.handler;

import xyz.zcraft.seira.addpp.AddPpApi;
import xyz.zcraft.seira.addpp.AddPpRequest;
import xyz.zcraft.seira.api.OstellaApi;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.command.parse.Resolver;
import xyz.zcraft.seira.command.reply.CommandUsage;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import static xyz.zcraft.seira.command.reply.ReplyFactory.at;

public final class AddPpCommandHandler {
    private final AddPpApi api;
    private final Function<String, Long> binding;

    public AddPpCommandHandler(AddPpApi api, Function<String, Long> binding) {
        this.api = api;
        this.binding = binding;
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
            if (result.replaced()) text.append("\n> 同图已有 BP：取更高 PP 替换，不重复计入。");
        } else text.append("\n> (视为不同谱面的新成绩)");
        text.append(String.format(Locale.ROOT, "\n总 PP：%.2f → __%.2f__ (+%.2f)", result.beforePp(), result.afterPp(), result.change()));
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
            var request = AddPpRequest.parse(ctx.args());
            Long uid = request.player() == null ? binding.apply(ctx.senderUserId())
                    : OstellaApi.resolveUid(new Resolver(binding).player(request.player(), ctx.senderUserId()));
            if (uid == null || uid <= 0) {
                ctx.sendReply(at(ctx) + CommandUsage.NO_BIND);
                return;
            }
            var query = request.resolveQuery(shortcut -> {
                String scoreId = OstellaApi.lookupPlayerScore(uid, shortcut.scoreList(), shortcut.index(), List.of(), null);
                return OstellaApi.getScoreBeatmapId(scoreId);
            });
            var result = api.estimate(uid, query);
            String target = request.player() == null ? "" : "目标玩家：" + uid + "\n";
            ctx.sendReply(at(ctx) + target + format(result));
        } catch (IllegalArgumentException e) {
            ctx.sendReply(at(ctx) + e.getMessage() + "\n> " + CommandUsage.ADDPP);
        }
    }
}
