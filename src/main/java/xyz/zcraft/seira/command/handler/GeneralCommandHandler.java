package xyz.zcraft.seira.command.handler;

import xyz.zcraft.osu.model.Beatmapset;
import xyz.zcraft.seira.api.APIHelper;
import xyz.zcraft.seira.bot.MessageSender;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.*;
import xyz.zcraft.seira.command.reply.ReplyFactory;
import xyz.zcraft.seira.data.UploadedImage;
import xyz.zcraft.seira.services.DailyLuck;

import java.util.function.Predicate;

public final class GeneralCommandHandler {
    private final MessageSender messageSender;
    private final TaskCoordinator taskCoordinator;
    private final ReplyFactory replyFactory;
    private final ScoreCommandHandler scoreCommands;
    private final Predicate<String> adminAuthorizer;
    private final Runnable commandMetric;

    public GeneralCommandHandler(
            MessageSender messageSender,
            TaskCoordinator taskCoordinator,
            ReplyFactory replyFactory,
            ScoreCommandHandler scoreCommands,
            Predicate<String> adminAuthorizer,
            Runnable commandMetric
    ) {
        this.messageSender = messageSender;
        this.taskCoordinator = taskCoordinator;
        this.replyFactory = replyFactory;
        this.scoreCommands = scoreCommands;
        this.adminAuthorizer = adminAuthorizer;
        this.commandMetric = commandMetric;
    }

    public void handleU(Context context) {
        if (context.argumentCount() != 1) {
            context.sendReply(PendingMessage.ofString("用法：/u <玩家ID/用户名/@用户>"));
            return;
        }

        String target = context.argument(0);
        Context bestScoreContext = context.asCommand("bo", new String[]{"8", target}, "8 " + target);

        // /u historically routed through /bo and therefore counted both commands.
        commandMetric.run();
        scoreCommands.handleBo(bestScoreContext);
    }

    public void handleLuck(Context context) {
        if (context.argumentCount() != 0) {
            context.sendReply(PendingMessage.ofString("用法：/luck"));
            return;
        }

        taskCoordinator.runApiRequest(context, "Luck", () -> {
            DailyLuck.Luck luck = DailyLuck.getLuck(context.senderUserId());
            Beatmapset mapset = APIHelper.getBeatmapsetRaw(luck.dailyMapset());
            UploadedImage cover = messageSender.uploadImageToCos(mapset.getCovers().getCover());
            context.sendReply(replyFactory.luckMessage(context, luck, mapset, cover));
        });
    }

    public void handleInspect(Context context) {
        context.sendReply(replyFactory.inspectMessage(
                context, context.senderUserId(), adminAuthorizer.test(context.senderUserId()),
                context.groupId(), context.messageId()
        ));
    }

    public void handleHelp(Context context) {
        context.sendReply(replyFactory.helpMessage(context));
    }

    public void handleFaq(Context context) {
        context.sendReply(replyFactory.faqMessage(context));
    }

    public void handleStat(Context context) {
        context.sendReply(replyFactory.statusMessage(context, APIHelper.getServerStatus()));
    }

    public void handleUnknown(Context context) {
        context.sendReply(PendingMessage.ofString("未知指令。使用/help获取帮助。"));
    }
}
