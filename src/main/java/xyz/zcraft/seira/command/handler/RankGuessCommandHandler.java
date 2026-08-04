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

import static xyz.zcraft.seira.command.reply.ReplyFactory.at;

public final class RankGuessCommandHandler {
    private static final String USAGE = "用法：/rg start | /rg #Rank | /rg end";

    private final TaskCoordinator taskCoordinator;
    private final ReplyFactory replyFactory;
    private final RankGuessGameService games;

    public RankGuessCommandHandler(
            TaskCoordinator taskCoordinator,
            ReplyFactory replyFactory,
            RankGuessGameService games
    ) {
        this.taskCoordinator = taskCoordinator;
        this.replyFactory = replyFactory;
        this.games = games;
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
        RankGuessGameService.Reservation reservation = games.reserve(ctx.groupId());
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
                    } else {
                        games.cancel(reservation);
                    }
                }
        );
    }

    private RouteDecision guess(Context ctx, long rank) {
        RankGuessGameService.GuessResult result = games.guess(ctx.groupId(), ctx.senderUserId(), rank);
        return switch (result.status()) {
            case NO_GAME -> RouteDecision.sync(PendingMessage.ofString("本群当前没有进行中的 Rank Guess 喵"));
            case STARTING -> RouteDecision.sync(PendingMessage.ofString("高光仍在渲染，请等待视频发送后再猜测喵"));
            case RECORDED -> RouteDecision.sync(PendingMessage.ofMarkdownRaw(
                    at(ctx) + "已记录你的猜测：`#" + String.format(Locale.US, "%,d", rank) + "`"
            ));
            case UPDATED -> RouteDecision.sync(PendingMessage.ofMarkdownRaw(
                    at(ctx) + "已更新你的猜测：`#" + String.format(Locale.US, "%,d", rank) + "`"
            ));
        };
    }

    private RouteDecision end(Context ctx) {
        RankGuessGameService.EndResult result = games.end(ctx.groupId());
        return switch (result.status()) {
            case NO_GAME -> RouteDecision.sync(PendingMessage.ofString("本群当前没有进行中的 Rank Guess 喵"));
            case STARTING -> RouteDecision.sync(PendingMessage.ofString("高光仍在渲染，请等待视频发送后再结束游戏喵"));
            case FINISHED -> RouteDecision.sync(replyFactory.rankGuessResultMessage(result.round()));
        };
    }

    private static Long parseRank(String argument) {
        if (!argument.matches("^#(?:[1-9]\\d*|[1-9]\\d{0,2}(?:,\\d{3})+)$")) {
            return null;
        }
        try {
            return Long.parseLong(argument.substring(1).replace(",", ""));
        } catch (NumberFormatException _) {
            return null;
        }
    }
}
