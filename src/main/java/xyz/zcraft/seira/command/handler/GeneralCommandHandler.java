package xyz.zcraft.seira.command.handler;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.osu.model.Beatmapset;
import xyz.zcraft.seira.api.AsteroidApi;
import xyz.zcraft.seira.api.OstellaApi;
import xyz.zcraft.seira.bot.MessageSender;
import xyz.zcraft.seira.bot.data.msg.PendingMessage;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.command.TargetHistory;
import xyz.zcraft.seira.command.TaskCoordinator;
import xyz.zcraft.seira.command.parse.Resolver;
import xyz.zcraft.seira.command.parse.TargetResolver;
import xyz.zcraft.seira.command.reply.ReplyFactory;
import xyz.zcraft.seira.data.Notice;
import xyz.zcraft.seira.data.UploadedImage;
import xyz.zcraft.seira.db.UserDataStore;
import xyz.zcraft.seira.services.DailyLuck;
import xyz.zcraft.seira.services.NoticeStore;
import xyz.zcraft.seira.util.dice.Dice;
import xyz.zcraft.seira.util.dice.expr.DiceExpr;
import xyz.zcraft.seira.util.dice.result.DiceResult;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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

    public void handleUserInfo(Context ctx) {
        String player = resolver.player(ctx.argumentCount() == 0 ? null : ctx.argument(0), ctx.senderUserId());

        try (var _ = taskCoordinator.beginRequest(ctx, "User Info")) {
            long uid = OstellaApi.resolveUid(player);
            var response = OstellaApi.getUserInfoResponse(uid);
            ctx.sendReply(PendingMessage.ofImageBase64(response.getContent().toBase64()));
//            var completion = replyFactory.userInfoMessage(context, response);
//            context.sendReply(taskCoordinator.imageMessage(response, completion));
        }
    }

    public void handleRemoveMember(Context ctx) {
        if (!ctx.inGroup()) {
            ctx.sendReply(at(ctx) + "本指令仅能在群组中使用喵。");
            return;
        }

        if (ctx.permissionLevel() < Context.Permission.ADMIN && !adminAuthorizer.test(ctx.senderUserId())) {
            ctx.sendReply(at(ctx) + "你没有权限使用此指令喵。");
            return;
        }

        if (ctx.argumentCount() != 1) {
            ctx.sendReply(at(ctx) + "用法：/rm <用户>");
            return;
        }

        String player = resolver.player(ctx.argument(0), ctx.senderUserId());
        long uid = OstellaApi.resolveUid(player);

        UserDataStore.findGroupOpenIdByUid(ctx.groupId(), uid).ifPresentOrElse(s -> {
            UserDataStore.removeGroupMember(ctx.groupId(), s);
            ctx.sendReply(at(ctx) + "已移除玩家 `%s` 在本群中的记录喵。".formatted(player));
        }, () -> ctx.sendReply(at(ctx) + "指定玩家在本群中无记录喵。"));
    }

    public void handleUserAvatar(Context ctx) {
        final Set<Long> uids = new HashSet<>();

        if (ctx.argumentCount() == 0) {
            uids.add(resolver.requireBind(ctx));
        } else {
            uids.addAll(Arrays.stream(ctx.args())
                    .map(e -> resolver.player(e, null))
                    .map(OstellaApi::resolveUid).toList());
        }

        try (var _ = taskCoordinator.beginRequest(ctx, "User Info")) {
            if (uids.size() == 1) {
                var response = OstellaApi.getUserCardResponse(uids.iterator().next());

                ctx.sendReply(PendingMessage.ofImageBase64(response.getContent().toBase64()));
            } else {
                List<UploadedImage> images = new ArrayList<>(uids.size());
                for (Long uid : uids) {
                    var response = OstellaApi.getUserCardResponse(uid);
                    final UploadedImage uploadedImage = messageSender.uploadImageToCos(response.getContent().bytes());
                    images.add(uploadedImage);
                }

                final String msg = images.stream()
                        .map(e -> e.toMarkdown(100, 120))
                        .collect(Collectors.joining(""))
                        .trim();

                ctx.sendReply(msg);
            }
        }
    }

    private static String roll(String... choices) {
        return choices[new Random().nextInt(choices.length)];
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
            } catch (Exception _) {
                tryParseRollSelection(ctx);
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

    private void tryParseRollSelection(Context ctx) {
        final String input = ctx.query().trim();
        final String result;

        Matcher matcher = Pattern.compile("(.+?)不\\1").matcher(input);

        if (matcher.find()) {
            result = matcher.replaceFirst(roll("", "不") + "$1");
        } else {
            String body = input.startsWith("是") ? input.substring(1) : input;
            String[] options = body.split("还是", -1);

            if (options.length < 2 || Arrays.stream(options).anyMatch(String::isBlank)) {
                ctx.sendReply(at(ctx) + "无法解析表达式喵。");
                return;
            }

            result = options[ThreadLocalRandom.current().nextInt(options.length)].trim();
        }

        ctx.sendReply(
                at(ctx) + roll(
                        "小星觉得应该 %s !",
                        "不如 %s ?",
                        "要不然 %s ?",
                        "当然 %s 啦!",
                        "还是 %s 吧!"
                ).formatted(result)
        );
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

    public void handleUserStat(Context ctx) {
        String player = resolver.player(ctx.argumentCount() == 0 ? null : ctx.argument(0), ctx.senderUserId());

        try (var _ = taskCoordinator.beginRequest(ctx, "User Info Short")) {
            long uid = OstellaApi.resolveUid(player);
            var user = OstellaApi.getUserRaw(uid);
            ctx.sendReply(replyFactory.userInfoShortMessage(ctx, user));
        }
    }

    public void handleBurningText(Context ctx) {
        if (ctx.argumentCount() == 0) {
            ctx.sendReply(at(ctx) + "用法：/burning <文字>");
            return;
        }

        var text = Resolver.parseQqMeme(ctx.query());
        if (text.length() > 50) {
            ctx.sendReply(at(ctx) + "文字太长了喵。");
            return;
        }

        try (var _ = taskCoordinator.beginRequest(ctx, "Burning Text")) {
            final byte[] burningText = AsteroidApi.getBurningText(text);
            ctx.sendReply(PendingMessage.ofImageBase64(Base64.getEncoder().encodeToString(burningText)));
        }
    }
}
