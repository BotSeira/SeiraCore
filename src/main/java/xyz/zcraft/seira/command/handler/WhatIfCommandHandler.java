package xyz.zcraft.seira.command.handler;

import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.command.reply.CommandUsage;
import xyz.zcraft.seira.whatif.RankPpModel;
import xyz.zcraft.seira.whatif.WhatIfQuery;
import xyz.zcraft.seira.whatif.WhatIfService;

import java.util.Locale;

import static xyz.zcraft.seira.command.reply.ReplyFactory.at;

public final class WhatIfCommandHandler {
    private final WhatIfService service;

    public WhatIfCommandHandler(WhatIfService service) {
        this.service = service;
    }

    public static String format(WhatIfQuery query, WhatIfService.State state) {
        RankPpModel model = state.model();
        String result;
        if (query.byPp()) {
            if (query.pp() > model.first().pp()) {
                result = model.first().rank() == 1
                        ? String.format(Locale.ROOT, "总 PP %.2fpp：预计可达全球 #1(超过样本榜首 PP)喵。", query.pp())
                        : String.format(Locale.ROOT, "总 PP %.2fpp：预计可达全球 #%,d 或更靠前(超过样本最高 PP)喵。",
                        query.pp(), model.first().rank());
            } else if (query.pp() < model.last().pp()) {
                result = String.format(Locale.ROOT, "总 PP %.2fpp：全球排名预计在 #%,d 之后，超出样本覆盖范围喵。",
                        query.pp(), model.last().rank());
            } else {
                result = String.format(Locale.ROOT, "总 PP %.2fpp ≈ 全球排名 __#%,d__ 喵", query.pp(),
                        Math.max(1, Math.round(model.rankAtPp(query.pp()))));
            }
        } else if (query.rank() < model.first().rank()) {
            result = String.format(Locale.ROOT, "全球排名 #%,d：所需总 PP 预计高于 %.2fpp，超出样本覆盖范围喵。",
                    query.rank(), model.first().pp());
        } else if (query.rank() > model.last().rank()) {
            result = String.format(Locale.ROOT, "全球排名 #%,d：所需总 PP 预计低于 %.2fpp，超出样本覆盖范围喵。",
                    query.rank(), model.last().pp());
        } else {
            result = String.format(Locale.ROOT, "全球排名 #%,d ≈ 总 PP __%.2fpp__ 喵", query.rank(), model.ppAtRank(query.rank()));
        }
        return result;
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
        ctx.sendReply(at(ctx) + format(query, service.current()));
    }
}
