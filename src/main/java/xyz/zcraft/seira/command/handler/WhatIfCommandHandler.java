package xyz.zcraft.seira.command.handler;

import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.command.reply.CommandUsage;
import xyz.zcraft.seira.whatif.WhatIfQuery;
import xyz.zcraft.seira.whatif.WhatIfApi;

import java.util.Locale;

import static xyz.zcraft.seira.command.reply.ReplyFactory.at;

public final class WhatIfCommandHandler {
    private final WhatIfApi service;

    public WhatIfCommandHandler(WhatIfApi service) {
        this.service = service;
    }

    public static String format(WhatIfQuery query, WhatIfApi.Result estimate) {
        String result;
        if (query.byPp()) {
            if ("HIGH_PP".equals(estimate.status())) {
                result = Math.round(estimate.rank()) == 1
                        ? String.format(Locale.ROOT, "总 PP %.2fpp：预计可达全球 #1(超过样本榜首 PP)喵。", query.pp())
                        : String.format(Locale.ROOT, "总 PP %.2fpp：预计可达全球 #%,d 或更靠前(超过样本最高 PP)喵。",
                        query.pp(), Math.round(estimate.rank()));
            } else if ("LOW_PP".equals(estimate.status())) {
                result = String.format(Locale.ROOT, "总 PP %.2fpp：全球排名预计在 #%,d 之后，超出样本覆盖范围喵。",
                        query.pp(), Math.round(estimate.rank()));
            } else {
                result = String.format(Locale.ROOT, "总 PP %.2fpp ≈ 全球排名 __#%,d__ 喵", query.pp(),
                        Math.max(1, Math.round(estimate.rank())));
            }
        } else if ("LOW_RANK".equals(estimate.status())) {
            result = String.format(Locale.ROOT, "全球排名 #%,d：所需总 PP 预计高于 %.2fpp，超出样本覆盖范围喵。",
                    query.rank(), estimate.pp());
        } else if ("HIGH_RANK".equals(estimate.status())) {
            result = String.format(Locale.ROOT, "全球排名 #%,d：所需总 PP 预计低于 %.2fpp，超出样本覆盖范围喵。",
                    query.rank(), estimate.pp());
        } else {
            result = String.format(Locale.ROOT, "全球排名 #%,d ≈ 总 PP __%.2fpp__ 喵", query.rank(), estimate.pp());
        }
        boolean stale = estimate.stale();
        return result + (stale ? "\n> 数据已经一段时间没有更新了喵，可能较不准确~" : "");
    }

    public void handleWhatIf(Context ctx) {
        if (ctx.argumentCount() != 1) {
            ctx.sendReply(at(ctx) + CommandUsage.WHATIF);
            return;
        }
        WhatIfQuery query;
        try {
            query = WhatIfQuery.parse(ctx.argument(0));
        } catch (IllegalArgumentException e) {
            ctx.sendReply(at(ctx) + e.getMessage() + "\n" + CommandUsage.WHATIF);
            return;
        }
        try {
            ctx.sendReply(at(ctx) + format(query, service.estimate(query)));
        } catch (IllegalStateException e) {
            ctx.sendReply(at(ctx) + e.getMessage());
        }
    }
}
