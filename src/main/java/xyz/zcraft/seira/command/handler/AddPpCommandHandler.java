package xyz.zcraft.seira.command.handler;

import xyz.zcraft.seira.addpp.AddPpApi;
import xyz.zcraft.seira.addpp.AddPpQuery;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.command.reply.CommandUsage;
import xyz.zcraft.seira.whatif.WhatIfService;

import java.util.Locale;
import java.util.function.Function;

import static xyz.zcraft.seira.command.reply.ReplyFactory.at;

public final class AddPpCommandHandler {
    private final AddPpApi api;
    private final WhatIfService ranks;
    private final Function<String, Long> binding;

    public AddPpCommandHandler(AddPpApi api, WhatIfService ranks, Function<String, Long> binding) {
        this.api = api;
        this.ranks = ranks;
        this.binding = binding;
    }

    public static String format(AddPpApi.Result result, WhatIfService.State state) {
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
            var model = state.model();
            boolean covered = result.afterPp() >= model.last().pp() && result.afterPp() <= model.first().pp();
            text.append("\n全球排名：").append(rank(result.rank())).append(" → ");
            if (covered) {
                double predicted = model.rankAtPp(result.afterPp());
                if (result.rank() != null && result.rank() > 0) {
                    if (result.beforePp() >= model.last().pp() && result.beforePp() <= model.first().pp())
                        predicted *= result.rank() / model.rankAtPp(result.beforePp());
                    predicted = Math.min(result.rank(), predicted);
                }
                long next = Math.max(1, Math.round(predicted));
                text.append("__#").append(next).append("__");
                if (result.rank() != null && result.rank() > 0)
                    text.append(" (+").append(result.rank() - next).append(")");
            } else if (result.afterPp() > model.first().pp()) {
                text.append(model.first().rank() == 1 ? "预计 #1" : "预计 #" + model.first().rank() + " 或更靠前");
            } else text.append("预计在 #").append(model.last().rank()).append(" 之后");
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
            var query = AddPpQuery.parse(ctx.args());
            Long uid = binding.apply(ctx.senderUserId());
            if (uid == null || uid <= 0) {
                ctx.sendReply(at(ctx) + CommandUsage.NO_BIND);
                return;
            }
            var result = api.estimate(uid, query);
            ctx.sendReply(at(ctx) + format(result, ranks.current()));
        } catch (IllegalArgumentException e) {
            ctx.sendReply(at(ctx) + e.getMessage() + "/n> " + CommandUsage.ADDPP);
        }
    }
}
