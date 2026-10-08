package xyz.zcraft.seira.command.handler;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.osu.model.Beatmapset;
import xyz.zcraft.seira.api.AsteroidApi;
import xyz.zcraft.seira.api.OstellaApi;
import xyz.zcraft.seira.api.data.MinecraftServerStatus;
import xyz.zcraft.seira.bot.MessageSender;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.command.TaskCoordinator;
import xyz.zcraft.seira.command.parse.Resolver;
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
import java.util.regex.Pattern;

import static xyz.zcraft.seira.command.reply.ReplyFactory.at;

public final class GeneralCommandHandler {
    private static final Pattern SERVER_PATTERN = Pattern.compile("^(?:https?://)?([a-zA-Z0-9-]+(?:\\.[a-zA-Z0-9-]+)+)(?::[0-9]+)?$");
    private final MessageSender messageSender;
    private final TaskCoordinator taskCoordinator;
    private final ReplyFactory replyFactory;
    private final Resolver resolver;
    private final Predicate<String> adminAuthorizer;
    private static final Logger LOG = LogManager.getLogger(GeneralCommandHandler.class);

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

    public void handleMc(Context ctx) {
        if (ctx.argumentCount() != 1) {
            ctx.sendReply(at(ctx) + "用法：/mc <服务器地址>");
            return;
        }

        try {
            final String address = ctx.argument(0);

            if (!SERVER_PATTERN.matcher(address).matches()) {
                ctx.sendReply(at(ctx) + "服务器地址无效喵。");
                return;
            }

            final var probe = AsteroidApi.getMinecraftServerStatus(address);

            final var status = probe.status();
            final var players = status.players();
            final var samples = players.sample();

            String playersSample = "";

            if (samples != null) {
                playersSample = String.join(", ", samples.stream().map(MinecraftServerStatus.Players.Sample::name).toList());
            }

            ctx.sendReply(at(ctx) + """
                             `%s` 的服务器状态：
                             - 版本: `%s`
                             - 描述: `%s`
                             - 延迟: `%d` ms
                             - 在线人数: `%d` / `%d`
                             - 在线玩家: [%s]
                            """.formatted(
                            address,
                            status.version().name(),
                            status.description(),
                            probe.latency(),
                            players.online(),
                            players.max(),
                            playersSample
                    )
            );
        } catch (Exception e) {
            ctx.sendReply(at(ctx) + "无法获取目标服务器状态喵，这可能是因为目标服务器未开启或者存在网络问题。");
            LOG.warn("Failed to probe Minecraft server", e);
        }
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
