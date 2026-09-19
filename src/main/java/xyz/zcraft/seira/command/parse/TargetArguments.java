package xyz.zcraft.seira.command.parse;

import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.command.TargetHistory;

import java.util.function.Predicate;

import static xyz.zcraft.seira.command.reply.ReplyFactory.at;

public final class TargetArguments {
    private TargetArguments() {}

    public static TargetResolution parse(Context ctx, Resolver resolver, TargetHistory.Ids previous,
                                         String usage, int maxOptions) {
        return parse(ctx, resolver, previous, usage, maxOptions, _ -> false);
    }

    public static TargetResolution parse(Context ctx, Resolver resolver, TargetHistory.Ids previous,
                                         String usage, int maxOptions, Predicate<String> option) {
        TargetResolution target = ctx.argumentCount() == 0 || option.test(ctx.argument(0))
                ? new TargetResolution(null, 0)
                : resolver.resolveTargetWithOptionalMention(ctx.args(), ctx.senderUserId());
        if (target.target() != null && target.target().isError()) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + target.target().errorMessage()));
            return null;
        }
        if ((target.target() == null && previous == null)
                || ctx.argumentCount() - target.consumedArgs() > maxOptions) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + usage));
            return null;
        }
        return target;
    }
}
