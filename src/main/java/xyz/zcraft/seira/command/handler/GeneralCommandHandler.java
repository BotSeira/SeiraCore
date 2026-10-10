package xyz.zcraft.seira.command.handler;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.osu.model.Beatmapset;
import xyz.zcraft.seira.api.AsteroidApi;
import xyz.zcraft.seira.api.OstellaApi;
import xyz.zcraft.seira.api.data.MinecraftServerStatus;
import xyz.zcraft.seira.api.data.ncm.Artist;
import xyz.zcraft.seira.api.data.ncm.MatchResult;
import xyz.zcraft.seira.api.data.ncm.Song;
import xyz.zcraft.seira.bot.MessageSender;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.command.TargetHistory;
import xyz.zcraft.seira.command.TaskCoordinator;
import xyz.zcraft.seira.command.parse.Resolver;
import xyz.zcraft.seira.command.parse.TargetInput;
import xyz.zcraft.seira.command.parse.TargetResolver;
import xyz.zcraft.seira.command.reply.ReplyFactory;
import xyz.zcraft.seira.data.Notice;
import xyz.zcraft.seira.data.UploadedImage;
import xyz.zcraft.seira.services.DailyLuck;
import xyz.zcraft.seira.services.NoticeStore;
import xyz.zcraft.seira.util.dice.Dice;
import xyz.zcraft.seira.util.dice.expr.DiceExpr;
import xyz.zcraft.seira.util.dice.result.DiceResult;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static xyz.zcraft.seira.command.reply.ReplyFactory.at;

public final class GeneralCommandHandler {
    private static final Pattern SERVER_PATTERN = Pattern.compile("^(?:https?://)?([a-zA-Z0-9-]+(?:\\.[a-zA-Z0-9-]+)+)(?::[0-9]+)?$");
    private static final Logger LOG = LogManager.getLogger(GeneralCommandHandler.class);
    private static final Pattern TITLE_PATTERN = Pattern.compile("^([^()]+)((?:\\(.+\\))+)$");
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

    public void handleNcm(Context ctx) {
        var target = ctx.argumentCount() == 0 ? TargetInput.memory() : TargetInput.read(ctx.args());
        var remembered = history.get(ctx);
        if ((target.kind() == TargetInput.Kind.MEMORY && remembered == null)
                || ctx.argumentCount() - target.consumedArgs() > 0) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "用法：/ncm <谱面集ID 或 快捷查询>"));
            return;
        }
        try (var _ = taskCoordinator.beginRequest(ctx, "Beatmapset")) {
            var ids = targets.beatmapset(ctx, target, remembered);
            history.remember(ctx, ids);
            final Beatmapset beatmapset = OstellaApi.getBeatmapsetRaw(ids.beatmapsetId());

            ctx.sendReply(
                    at(ctx) + "正在网易云音乐搜索 `%s` 喵..."
                            .formatted(beatmapset.getTitleUnicode() + " - " + beatmapset.getArtistUnicode())
            );

            StringBuilder sb = new StringBuilder(at(ctx) + "网易云音乐搜索结果:\n");

            final List<MatchResult> matchResults = AsteroidApi.matchSong(ids.beatmapsetId());

            final Set<Long> listedIds = new HashSet<>();

            if (matchResults.isEmpty()) {
                sb.append("听歌识曲未找到结果喵。\n");
            } else {
                sb.append("| 来源: | 听歌识曲 | 网易云 |\n|:---|:---|:---:|\n");
                for (int i = 0; i < Math.min(matchResults.size(), 5); i++) {
                    MatchResult result = matchResults.get(i);
                    final Matcher matcher = TITLE_PATTERN.matcher(result.song().name());
                    String title;
                    String subtitle;
                    if (matcher.matches() && matcher.groupCount() == 2) {
                        title = matcher.group(1);
                        subtitle = matcher.group(2);
                    } else {
                        title = result.song().name();
                        subtitle = "";
                    }
                    sb.append("| ![Cover #80px #80px](%s) | $\\begin{array}{l}\\large{\\textbf{%s}}%s\\\\\\small{\\textsf{%s}}\\\\\\tiny{\\textsf{%s}}\\end{array}$ | [打开](https://music.163.com/song?id=%d) |".formatted(
                            result.song().album().picUrl(),
                            escape(title),
                            !subtitle.isEmpty() ? "\\\\\\small{\\textit{%s}}".formatted(escape(subtitle)) : "",
                            escape("By: " + result.song().artists().stream().map(Artist::name).collect(Collectors.joining(", "))),
                            escape("专辑: " + result.song().album().name()),
                            result.song().id()
                    )).append("\n");
                    listedIds.add(result.song().id());
                }
            }

            sb.append("\n");

            final List<Song> searchResults = AsteroidApi.searchSong(beatmapset.getTitleUnicode() + " " + beatmapset.getArtistUnicode());

            if (searchResults.isEmpty()) {
                sb.append("搜索未找到结果喵。\n");
            } else {
                sb.append("| 来源: 搜索 | 网易云 |\n|:---|:---:|\n");
                for (int i = 0; i < Math.min(searchResults.size(), 2); i++) {
                    Song result = searchResults.get(i);
                    if (listedIds.contains(result.id())) continue;

                    sb.append("| $\\begin{array}{l}\\large{\\textbf{%s}}\\\\\\small{\\textsf{%s}}\\\\\\tiny{\\textsf{%s}}\\end{array}$ | [打开](https://music.163.com/song?id=%d) |".formatted(
                            escape(result.name()),
                            escape("By: " + result.artists().stream().map(Artist::name).collect(Collectors.joining(", "))),
                            escape("专辑: " + result.album().name()),
                            result.id()
                    )).append("\n");
                }
            }

            ctx.sendReply(sb.toString().trim());
        }
    }

    public String escape(String text) {
        return text.replaceAll("([\\\\$%#&_])", "\\\\$1");
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
