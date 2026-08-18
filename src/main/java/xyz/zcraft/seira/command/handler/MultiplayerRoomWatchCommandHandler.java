package xyz.zcraft.seira.command.handler;

import xyz.zcraft.osu.model.MultiplayerRoom;
import xyz.zcraft.seira.api.APIHelper;
import xyz.zcraft.seira.api.data.OsuToken;
import xyz.zcraft.seira.api.data.Response;
import xyz.zcraft.seira.binding.UserDataStore;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.command.ResolutionException;
import xyz.zcraft.seira.command.TaskCoordinator;
import xyz.zcraft.seira.watch.MultiplayerRoomWatchService;
import xyz.zcraft.seira.watch.RoomWatchView;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MultiplayerRoomWatchCommandHandler {
    private static final String USAGE =
            "用法：/mpwatch start <房间ID/房间链接>；/mpwatch stop；/mpwatch status";
    private static final Pattern ROOM_URL = Pattern.compile(
            "(?i)^https?://(?:www\\.)?osu\\.ppy\\.sh/multiplayer/rooms/(\\d+)(?:[/?#].*)?$"
    );

    private final TaskCoordinator taskCoordinator;
    private final MultiplayerRoomWatchService watchService;

    public MultiplayerRoomWatchCommandHandler(
            TaskCoordinator taskCoordinator,
            MultiplayerRoomWatchService watchService
    ) {
        this.taskCoordinator = Objects.requireNonNull(taskCoordinator);
        this.watchService = Objects.requireNonNull(watchService);
    }

    public void handleMpWatch(Context ctx) {
        if (!ctx.inGroup()) {
            ctx.sendReply(PendingMessage.ofString("/mpwatch 仅支持群聊使用。"));
            return;
        }

        if (ctx.argumentCount() == 0) {
            handleStart(ctx, true);
            return;
        }

        switch (ctx.argument(0).toLowerCase(Locale.ROOT)) {
            case "start" -> handleStart(ctx, false);
            case "stop" -> handleStop(ctx);
            case "status", "list" -> handleStatus(ctx);
            default -> usage(ctx);
        }
    }

    private void handleStart(Context ctx, boolean current) {
        if (ctx.argumentCount() != 2 && !current) {
            usage(ctx);
            return;
        }

        Long roomId;

        if (!current) {
            roomId = parseRoomId(ctx.argument(1));
        } else {
            final OsuToken osuToken = UserDataStore.findOsuToken(ctx.senderUserId());
            if (osuToken == null) {
                ctx.sendReply("由于未绑定账户，无法获取当前房间~");
                return;
            }
            final Response<MultiplayerRoom> multiplayerRoom = APIHelper.getMultiplayerRoom(osuToken.accessToken());
            roomId = multiplayerRoom.getContent().getId();
        }

        if (roomId == null) {
            ctx.sendReply(PendingMessage.ofString("房间 ID 或链接格式不正确。\n" + USAGE));
            return;
        }

        taskCoordinator.runApiRequest(ctx, "Start Multiplayer Room Watch", () -> {
            if (!ctx.sendMessage(PendingMessage.ofString("正在尝试启动多人房间监视……"))) {
                ctx.sendReply(PendingMessage.ofString(
                        "由于缺少主动消息权限，无法启动监视！权限配置请见：https://docs.seira.top/overview/use.html#extra-permission"
                ));
                return;
            }
            try {
                RoomWatchView view = watchService.watch(ctx.groupId(), roomId);
                ctx.sendReply(PendingMessage.ofString(
                        "已开始监视多人房间“" + view.roomName() + "” (#" + view.roomId() + ")。"
                                + "之后完成的每张图都会自动推送结果。"
                ));
            } catch (IllegalArgumentException | IllegalStateException e) {
                throw new ResolutionException(e.getMessage());
            }
        });
    }

    private void handleStop(Context ctx) {
        if (ctx.argumentCount() != 1) {
            usage(ctx);
            return;
        }
        RoomWatchView stopped = watchService.stop(ctx.groupId());
        ctx.sendReply(PendingMessage.ofString(
                stopped == null
                        ? "当前群聊没有多人房间监视。"
                        : "已停止监视多人房间“" + stopped.roomName() + "” (#" + stopped.roomId() + ")。"
        ));
    }

    private void handleStatus(Context ctx) {
        if (ctx.argumentCount() != 1) {
            usage(ctx);
            return;
        }
        RoomWatchView view = watchService.get(ctx.groupId());
        ctx.sendReply(PendingMessage.ofString(
                view == null
                        ? "当前群聊没有多人房间监视。"
                        : "当前正在监视多人房间“" + view.roomName() + "” (#" + view.roomId() + ")。"
        ));
    }

    static Long parseRoomId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        String numeric = normalized;
        Matcher matcher = ROOM_URL.matcher(normalized);
        if (matcher.matches()) {
            numeric = matcher.group(1);
        } else if (!normalized.matches("\\d+")) {
            return null;
        }
        try {
            long roomId = Long.parseLong(numeric);
            return roomId > 0 ? roomId : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static void usage(Context ctx) {
        ctx.sendReply(PendingMessage.ofString(USAGE));
    }
}
