package xyz.zcraft.seira.command.handler;

import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.command.TaskCoordinator;
import xyz.zcraft.seira.watch.ScoreWatchService;
import xyz.zcraft.seira.watch.SpecificScoreWatchState;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class SpecificScoreWatchCommandHandler {
    private static final String USAGE =
            "用法：/wx start <UID列表，逗号分隔> <谱面ID列表，逗号分隔>；/wx stop";

    private final TaskCoordinator taskCoordinator;
    private final ScoreWatchService watchService;

    public SpecificScoreWatchCommandHandler(TaskCoordinator taskCoordinator, ScoreWatchService watchService) {
        this.taskCoordinator = Objects.requireNonNull(taskCoordinator);
        this.watchService = Objects.requireNonNull(watchService);
    }

    static Set<Long> parseIds(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (String part : value.split("[,，]", -1)) {
            try {
                long id = Long.parseLong(part.trim());
                if (id <= 0) {
                    return null;
                }
                ids.add(id);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return ids.isEmpty() ? null : Set.copyOf(ids);
    }

    public void handleWx(Context ctx) {
        if (!ctx.inGroup()) {
            ctx.sendReply(PendingMessage.ofString("/wx 仅支持群聊使用。"));
            return;
        }
        if (ctx.argumentCount() == 0) {
            usage(ctx);
            return;
        }

        switch (ctx.argument(0).toLowerCase(Locale.ROOT)) {
            case "start" -> handleStart(ctx);
            case "stop" -> handleStop(ctx);
            default -> usage(ctx);
        }
    }

    private void handleStart(Context ctx) {
        if (ctx.argumentCount() != 3) {
            usage(ctx);
            return;
        }

        Set<Long> userIds = parseIds(ctx.argument(1));
        Set<Long> beatmapIds = parseIds(ctx.argument(2));
        if (userIds == null || beatmapIds == null) {
            ctx.sendReply(PendingMessage.ofString("UID 与谱面 ID 必须是以逗号分隔的正整数。\n" + USAGE));
            return;
        }

        taskCoordinator.runApiRequest(ctx, "Start Specific Score Watch", () -> {
            if (!ctx.sendMessage(PendingMessage.ofString("正在尝试启动指定谱面成绩监视……")).success()) {
                ctx.sendReply(PendingMessage.ofString(
                        "由于缺少主动消息权限，无法启动监视！权限配置请见：https://docs.seira.top/overview/use.html#extra-permission"
                ));
                return;
            }
            SpecificScoreWatchState state = watchService.startSpecific(ctx.groupId(), userIds, beatmapIds);
            ctx.sendReply(PendingMessage.ofString(
                    "指定谱面成绩监视已启动，目标为" + state.userIds().size() + " 名玩家，"
                            + state.beatmapIds().size() + " 张谱面。"
            ));
        });
    }

    private void handleStop(Context ctx) {
        if (ctx.argumentCount() != 1) {
            usage(ctx);
            return;
        }
        boolean stopped = watchService.stopSpecific(ctx.groupId());
        ctx.sendReply(PendingMessage.ofString(
                stopped ? "已停止当前群聊的指定谱面成绩监视。" : "当前群聊没有指定谱面成绩监视。"
        ));
    }

    private void usage(Context ctx) {
        ctx.sendReply(PendingMessage.ofString(USAGE));
    }
}
