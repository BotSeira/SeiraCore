package xyz.zcraft.seira.command.handler;

import xyz.zcraft.osu.model.Beatmapset;
import xyz.zcraft.seira.api.APIHelper;
import xyz.zcraft.seira.bot.MessageSender;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.*;
import xyz.zcraft.seira.command.iface.CommandMetrics;
import xyz.zcraft.seira.command.reply.ReplyFactory;
import xyz.zcraft.seira.command.route.RouteDecision;
import xyz.zcraft.seira.data.UploadedImage;
import xyz.zcraft.seira.services.DailyLuck;

import java.util.function.Predicate;

public final class GeneralCommandHandler {
    private final MessageSender messageSender;
    private final TaskCoordinator taskCoordinator;
    private final ReplyFactory replyFactory;
    private final ScoreCommandHandler scoreCommands;
    private final Predicate<String> adminAuthorizer;
    private final CommandMetrics metrics;

    public GeneralCommandHandler(
            MessageSender messageSender,
            TaskCoordinator taskCoordinator,
            ReplyFactory replyFactory,
            ScoreCommandHandler scoreCommands,
            Predicate<String> adminAuthorizer,
            CommandMetrics metrics
    ) {
        this.messageSender = messageSender;
        this.taskCoordinator = taskCoordinator;
        this.replyFactory = replyFactory;
        this.scoreCommands = scoreCommands;
        this.adminAuthorizer = adminAuthorizer;
        this.metrics = metrics;
    }

    public RouteDecision handleU(Context context) {
        if (context.argumentCount() != 1) {
            return RouteDecision.sync(PendingMessage.ofString("用法：/u <玩家ID/用户名/@用户>"));
        }

        String target = context.argument(0);
        Context bestScoreContext = new Context(
                context.senderUserId(), context.groupId(), context.messageId(), "bo",
                new String[]{"8", target}, "8 " + target
        );

        // /u historically routed through /bo and therefore counted both commands.
        metrics.commandReceived();
        return scoreCommands.handleBo(bestScoreContext);
    }

    public RouteDecision handleLuck(Context context) {
        if (context.argumentCount() != 0) {
            return RouteDecision.sync(PendingMessage.ofString("用法：/luck"));
        }

        return taskCoordinator.queueApiRequest(context, "Luck", () -> {
            DailyLuck.Luck luck = DailyLuck.getLuck(context.senderUserId());
            Beatmapset mapset = APIHelper.getBeatmapsetRaw(luck.dailyMapset());
            UploadedImage cover = messageSender.uploadImageToCos(mapset.getCovers().getCover());
            return replyFactory.luckMessage(context, luck, mapset, cover);
        });
    }

    public RouteDecision handleInspect(Context context) {
        return RouteDecision.sync(replyFactory.inspectMessage(
                context, context.senderUserId(), adminAuthorizer.test(context.senderUserId()),
                context.groupId(), context.messageId()
        ));
    }

    public RouteDecision handleHelp(Context context) {
        return RouteDecision.sync(replyFactory.helpMessage(context));
    }

    public RouteDecision handleFaq(Context context) {
        return RouteDecision.sync(replyFactory.faqMessage(context));
    }

    public RouteDecision handleStat(Context context) {
        return RouteDecision.sync(replyFactory.statusMessage(context, APIHelper.getServerStatus()));
    }

    public RouteDecision handleUnknown() {
        return RouteDecision.sync(PendingMessage.ofString("未知指令。使用/help获取帮助。"));
    }
}
