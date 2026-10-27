package xyz.zcraft.seira.command.handler;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.osu.model.Beatmapset;
import xyz.zcraft.seira.api.AsteroidApi;
import xyz.zcraft.seira.api.OstellaApi;
import xyz.zcraft.seira.bot.MessageSender;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.command.TargetHistory;
import xyz.zcraft.seira.command.TaskCoordinator;
import xyz.zcraft.seira.command.parse.Resolver;
import xyz.zcraft.seira.command.parse.TargetResolver;
import xyz.zcraft.seira.command.reply.ReplyFactory;
import xyz.zcraft.seira.data.Notice;
import xyz.zcraft.seira.data.UploadedImage;
import xyz.zcraft.seira.services.DailyLuck;
import xyz.zcraft.seira.services.NoticeStore;
import xyz.zcraft.seira.util.dice.Dice;
import xyz.zcraft.seira.util.dice.expr.DiceExpr;
import xyz.zcraft.seira.util.dice.result.DiceResult;

import java.util.Objects;
import java.util.function.Predicate;

import static xyz.zcraft.seira.command.reply.ReplyFactory.at;

public final class GeneralCommandHandler {
    private static final Logger LOG = LogManager.getLogger(GeneralCommandHandler.class);
    private final MessageSender messageSender;
    private final TargetResolver targets;
    private final TargetHistory history;
    private final TaskCoordinator taskCoordinator;
    private final ReplyFactory replyFactory;
    private final Resolver resolver;
    private final Predicate<String> adminAuthorizer;

    public GeneralCommandHandler(
            MessageSender messageSender,
            TaskCoordinator taskCoordinator,
            ReplyFactory replyFactory,
            Resolver resolver,
            TargetResolver targets,
            TargetHistory history,
            Predicate<String> adminAuthorizer
    ) {
        this.messageSender = messageSender;
        this.taskCoordinator = taskCoordinator;
        this.replyFactory = replyFactory;
        this.resolver = resolver;
        this.targets = targets;
        this.history = history;
        this.adminAuthorizer = adminAuthorizer;
    }

    public void handleU(Context context) {
        String player = resolver.player(context.argumentCount() == 0 ? null : context.argument(0), context.senderUserId());

        try (var _ = taskCoordinator.beginRequest(context, "User Info")) {
            long uid = OstellaApi.resolveUid(player);
            var response = OstellaApi.getUserInfoResponse(uid);
            var completion = replyFactory.userInfoMessage(context, response);
            context.sendReply(taskCoordinator.imageMessage(response, completion));
        }
    }

    public void handleRoll(Context ctx) {
        DiceExpr diceExpr;

        if (ctx.argumentCount() == 0) {
            diceExpr = DiceExpr.HUNDRED;
        } else if ("0d00".equals(ctx.argument(0))) {
            ctx.sendReply(at(ctx) + "0d00 = __0721__");
            return;
        } else if (Resolver.parsePositiveLong(ctx.argument(0)) != null) {
            diceExpr = DiceExpr.parse("1d" + Resolver.parsePositiveLong(ctx.argument(0)));
        } else {
            try {
                diceExpr = DiceExpr.parse(ctx.query());
            } catch (Exception e) {
                ctx.sendReply(at(ctx) + "无法解析骰子表达式喵。");
                return;
            }
        }

        final Dice dice = new Dice();
        final DiceResult rollResult = dice.roll(diceExpr);

        final String rollResultStr = rollResult.toString();
        final String totalStr = String.valueOf(rollResult.total());

        final String message = at(ctx) + diceExpr + (Objects.equals(rollResultStr, totalStr) ? "" : " = " + rollResultStr) + " = __" + totalStr + "__";
        ctx.sendReply(message.replace("*", "\\*"));
    }

    public void handleLuck(Context context) {
        if (context.argumentCount() != 0) {
            context.sendReply(PendingMessage.ofMarkdownRaw(at(context) + "用法：/luck"));
            return;
        }

        try (var _ = taskCoordinator.beginRequest(context, "Luck")) {
            DailyLuck.Luck luck = DailyLuck.getLuck(context.senderUserId());
            Beatmapset mapset = OstellaApi.getBeatmapsetRaw(luck.dailyMapset());
            UploadedImage cover = messageSender.uploadImageToCos(mapset.getCovers().getCover());
            context.sendReply(replyFactory.luckMessage(context, luck, mapset, cover));
        }
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

    public void handleUsages(Context context) {
        context.sendReply(replyFactory.usagesMessage(context));
    }

    public void handleFaq(Context context) {
        context.sendReply(replyFactory.faqMessage(context));
    }

    public void handleStat(Context context) {
        context.sendReply(replyFactory.statusMessage(context, OstellaApi.getServerStatus(), AsteroidApi.getServerStatus()));
    }

    public void handleUnknown(Context context) {
        if (!context.inGroup()) {
            context.sendReply(PendingMessage.ofMarkdownRaw(at(context) + "未知指令。使用/help获取帮助。"));
        }
    }

    public void handleNotice(Context context) {
        if (context.argumentCount() != 0 && context.argumentCount() != 1) {
            context.sendReply(PendingMessage.ofMarkdownRaw(at(context) + "用法：/notice [公告ID]"));
            return;
        }

        long noticeId;

        if (context.argumentCount() == 1) {
            noticeId = Long.parseLong(context.argument(0));
        } else {
            noticeId = NoticeStore.getNewestId();
        }

        StringBuilder sb = new StringBuilder();
        NoticeStore.getNotices().stream()
                .filter(notice -> notice.id() == noticeId)
                .filter(Notice::isActive)
                .findFirst()
                .ifPresentOrElse(notice -> {
                    sb.append(at(context)).append("公告 `#").append(notice.id()).append("` - `").append(notice.title()).append("`\n");
                    sb.append(NoticeStore.getContentFor(notice));
                }, () -> sb.append(at(context)).append("未找到公告#").append(noticeId));

        context.sendReply(PendingMessage.ofMarkdownRaw(sb.toString()));
    }

    public void handleUx(Context ctx) {
        String player = resolver.player(ctx.argumentCount() == 0 ? null : ctx.argument(0), ctx.senderUserId());

        try (var _ = taskCoordinator.beginRequest(ctx, "User Info Short")) {
            long uid = OstellaApi.resolveUid(player);
            var user = OstellaApi.getUserRaw(uid);
            ctx.sendReply(replyFactory.userInfoShortMessage(ctx, user));
        }
    }
}
