package xyz.zcraft.seira.command.handler;

import xyz.zcraft.osu.model.Beatmapset;
import xyz.zcraft.seira.api.APIHelper;
import xyz.zcraft.seira.bot.MessageSender;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.command.TaskCoordinator;
import xyz.zcraft.seira.command.parse.Resolver;
import xyz.zcraft.seira.command.parse.UserRefResolution;
import xyz.zcraft.seira.command.reply.ReplyFactory;
import xyz.zcraft.seira.data.UploadedImage;
import xyz.zcraft.seira.data.UserRef;
import xyz.zcraft.seira.services.DailyLuck;

import java.util.function.Predicate;

public final class GeneralCommandHandler {
    private final MessageSender messageSender;
    private final TaskCoordinator taskCoordinator;
    private final ReplyFactory replyFactory;
    private final Resolver resolver;
    private final Predicate<String> adminAuthorizer;

    public GeneralCommandHandler(
            MessageSender messageSender,
            TaskCoordinator taskCoordinator,
            ReplyFactory replyFactory,
            Resolver resolver,
            Predicate<String> adminAuthorizer
    ) {
        this.messageSender = messageSender;
        this.taskCoordinator = taskCoordinator;
        this.replyFactory = replyFactory;
        this.resolver = resolver;
        this.adminAuthorizer = adminAuthorizer;
    }

    public void handleU(Context context) {
        UserRef userRef;
        if (context.argumentCount() == 0) {
            Long boundUid = resolver.resolveBoundUid(context.senderUserId());
            userRef = boundUid == null ? null : new UserRef.ByUid(boundUid);
        } else {
            UserRefResolution target = resolver.resolveUserRefArgument(context.argument(0));
            if (target.errorMessage() != null) {
                context.sendReply(PendingMessage.ofString(target.errorMessage()));
                return;
            }
            userRef = target.userRef();
        }

        if (userRef == null) {
            context.sendReply(PendingMessage.ofString("用法：/u [玩家ID/用户名/@用户]"));
            return;
        }

        taskCoordinator.runImageRequest(
                context,
                "User Info",
                () -> APIHelper.getUserInfoResponse(userRef),
                replyFactory::userInfoMessage
        );
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
