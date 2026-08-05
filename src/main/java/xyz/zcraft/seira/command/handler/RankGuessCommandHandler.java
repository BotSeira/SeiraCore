package xyz.zcraft.seira.command.handler;

import xyz.zcraft.seira.api.APIHelper;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.command.TaskCoordinator;
import xyz.zcraft.seira.command.reply.ReplyFactory;
import xyz.zcraft.seira.command.route.RouteDecision;
import xyz.zcraft.seira.game.RankGuessGameService;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static xyz.zcraft.seira.command.reply.ReplyFactory.at;

public final class RankGuessCommandHandler {
    private static final String USAGE = "用法：/rg start | /rg #Rank | /rg end";

    private final TaskCoordinator taskCoordinator;
    private final ReplyFactory replyFactory;
    private final RankGuessGameService games;
    private final Predicate<String> adminAuthorizer;

    public RankGuessCommandHandler(
            TaskCoordinator taskCoordinator,
            ReplyFactory replyFactory,
            RankGuessGameService games,
            Predicate<String> adminAuthorizer
    ) {
        this.taskCoordinator = taskCoordinator;
        this.replyFactory = replyFactory;
        this.games = games;
        this.adminAuthorizer = adminAuthorizer;
    }

    public RouteDecision handleRankGuess(Context ctx) {
        if (!ctx.inGroup()) {
            return RouteDecision.sync(PendingMessage.ofString("/rg 仅支持群聊使用。"));
        }
        if (ctx.argumentCount() != 1) {
            return RouteDecision.sync(PendingMessage.ofString(USAGE));
        }

        String argument = ctx.argument(0);
        if ("start".equalsIgnoreCase(argument)) {
            return start(ctx);
        }
        if ("end".equalsIgnoreCase(argument)) {
            return end(ctx);
        }

        Long rank = parseRank(argument);
        if (rank == null) {
            return RouteDecision.sync(PendingMessage.ofString(USAGE));
        }
        return guess(ctx, rank);
    }

    private RouteDecision start(Context ctx) {
        RankGuessGameService.Reservation reservation = games.reserve(ctx.groupId(), ctx.senderUserId());
        if (reservation == null) {
            return RouteDecision.sync(PendingMessage.ofString("本群已有一轮 Rank Guess 正在进行。"));
        }

        AtomicReference<RankGuessGameService.Round> roundRef = new AtomicReference<>();
        return taskCoordinator.queueReplayTask(
                ctx,
                "Rank Guess Render",
                () -> {
                    var randomScore = APIHelper.getRandomScore();
                    RankGuessGameService.Round round = RankGuessGameService.Round.from(randomScore);
                    roundRef.set(round);
                    return APIHelper.createObscuredReplayRenderTask(round.scoreId());
                },
                (context, _) -> PendingMessage.ofMarkdownRaw(
                        at(context) + "随机用户与成绩已选定，正在渲染回放片段，视频发送后即可开始猜测~"
                ),
                successful -> {
                    if (successful) {
                        games.activate(reservation, roundRef.get());
                        return PendingMessage.ofMarkdownRaw("回放渲染完成，游戏已开始！请在群内发送 `/rg #Rank` 猜测排名~");
                    } else {
                        games.cancel(reservation);
                        return PendingMessage.ofMarkdownRaw("由于回放渲染失败，本轮游戏已取消~");
                    }
                }
        );
    }

    private RouteDecision guess(Context ctx, long rank) {
        RankGuessGameService.GuessResult result = games.guess(ctx.groupId(), ctx.senderUserId(), rank);
        return switch (result.status()) {
            case NO_GAME -> RouteDecision.sync(PendingMessage.ofString("本群当前没有进行中的 Rank Guess 喵"));
            case STARTING -> RouteDecision.sync(PendingMessage.ofString("回放仍在渲染，请等待视频发送后再猜测喵"));
            case UPDATED, RECORDED -> RouteDecision.sync(PendingMessage.ofMarkdownRaw(
                    at(ctx)
                            + "已" + (result.status() == RankGuessGameService.GuessStatus.UPDATED ? "更新" : "记录") + "你的猜测："
                            + "`#" + String.format(Locale.US, "%,d", rank) + "`"
                            + " " + result.getMultipliersString()
            ));
        };
    }

    private RouteDecision end(Context ctx) {
        RankGuessGameService.EndResult result = games.end(
                ctx.groupId(), ctx.senderUserId(), adminAuthorizer.test(ctx.senderUserId())
        );
        return switch (result.status()) {
            case NO_GAME -> RouteDecision.sync(PendingMessage.ofString("本群当前没有进行中的 Rank Guess 喵"));
            case STARTING -> RouteDecision.sync(PendingMessage.ofString("高光仍在渲染，请等待视频发送后再结束游戏喵"));
            case FORBIDDEN -> RouteDecision.sync(PendingMessage.ofString(
                    "开始猜测后的3分钟内，仅发起者和机器人管理员可以结束游戏喵"
            ));
            case FINISHED -> RouteDecision.sync(replyFactory.rankGuessResultMessage(result.round()));
        };
    }

    private static final Pattern RANK_PATTERN = Pattern.compile("^#(\\d+)[wk]?$");

    private static Long parseRank(String argument) {
        final Matcher matcher = RANK_PATTERN.matcher(argument);
        if (!matcher.matches()) {
            return null;
        }
        try {
            long base = Long.parseLong(matcher.group(1));
            long multiplier = 1;

            if (argument.endsWith("w")) {
                multiplier = 10000;
            } else if (argument.endsWith("k")) {
                multiplier = 1000;
            }
            return base * multiplier;
        } catch (NumberFormatException _) {
            return null;
        }
    }
}
