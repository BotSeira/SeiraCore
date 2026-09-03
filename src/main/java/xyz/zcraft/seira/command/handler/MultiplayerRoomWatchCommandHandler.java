package xyz.zcraft.seira.command.handler;

import xyz.zcraft.osu.model.MultiplayerRoom;
import xyz.zcraft.seira.api.APIHelper;
import xyz.zcraft.seira.api.data.OsuToken;
import xyz.zcraft.seira.api.data.Response;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.command.ResolutionException;
import xyz.zcraft.seira.command.TaskCoordinator;
import xyz.zcraft.seira.db.UserDataStore;
import xyz.zcraft.seira.watch.MultiplayerRoomVersion;
import xyz.zcraft.seira.watch.MultiplayerRoomWatchService;
import xyz.zcraft.seira.watch.RoomWatchView;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MultiplayerRoomWatchCommandHandler {
    private static final String USAGE =
            "用法：/mpwatch [start] <房间ID> [stable|lazer]；"
                    + "/mpwatch [start] <房间链接>；/mpwatch stop [all]；/mpwatch status";
    private static final Pattern LAZER_ROOM_URL = Pattern.compile(
            "(?i)^https?://(?:www\\.)?osu\\.ppy\\.sh/multiplayer/rooms/(\\d+)(?:[/?#].*)?$"
    );
    private static final Pattern STABLE_ROOM_URL = Pattern.compile(
            "(?i)^https?://(?:www\\.)?osu\\.ppy\\.sh/(?:community/matches|mp)/(\\d+)(?:[/?#].*)?$"
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

    static RoomTarget parseRoomTarget(String value, String explicitVersion) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        String numeric = normalized;
        MultiplayerRoomVersion inferredVersion = null;
        Matcher lazerMatcher = LAZER_ROOM_URL.matcher(normalized);
        Matcher stableMatcher = STABLE_ROOM_URL.matcher(normalized);
        if (lazerMatcher.matches()) {
            numeric = lazerMatcher.group(1);
            inferredVersion = MultiplayerRoomVersion.LAZER;
        } else if (stableMatcher.matches()) {
            numeric = stableMatcher.group(1);
            inferredVersion = MultiplayerRoomVersion.STABLE;
        } else if (!normalized.matches("\\d+")) {
            return null;
        }

        MultiplayerRoomVersion requestedVersion = explicitVersion == null
                ? null
                : MultiplayerRoomVersion.parse(explicitVersion);
        if (explicitVersion != null && requestedVersion == null) {
            return null;
        }
        if (inferredVersion != null && requestedVersion != null && inferredVersion != requestedVersion) {
            return null;
        }
        MultiplayerRoomVersion version = inferredVersion != null
                ? inferredVersion
                : requestedVersion == null ? MultiplayerRoomVersion.LAZER : requestedVersion;
        try {
            long roomId = Long.parseLong(numeric);
            return roomId > 0 ? new RoomTarget(roomId, version) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String formatRoom(RoomWatchView view) {
        return view.version().value() + " 多人房间“" + view.roomName() + "” (#" + view.roomId() + ")";
    }

    private static void usage(Context ctx) {
        ctx.sendReply(PendingMessage.ofString(USAGE));
    }

    public void handleMpWatch(Context ctx) {
        if (!ctx.inGroup()) {
            ctx.sendReply(PendingMessage.ofString("/mpwatch 仅支持群聊使用。"));
            return;
        }

        if (ctx.argumentCount() == 0) {
            handleStart(ctx, 0);
            return;
        }

        switch (ctx.argument(0).toLowerCase(Locale.ROOT)) {
            case "start" -> handleStart(ctx, 1);
            case "stop" -> handleStop(ctx);
            case "status", "list" -> handleStatus(ctx);
            default -> handleStart(ctx, 0);
        }
    }

    private void handleStart(Context ctx, int argumentOffset) {
        int startArgumentCount = ctx.argumentCount() - argumentOffset;
        if (startArgumentCount < 0 || startArgumentCount > 2) {
            usage(ctx);
            return;
        }

        RoomTarget target;
        if (startArgumentCount == 0) {
            final OsuToken osuToken = UserDataStore.findOsuToken(ctx.senderUserId());
            if (osuToken == null) {
                ctx.sendReply("由于未绑定账户，无法获取当前房间，请手动提供ID~");
                return;
            }
            final Response<MultiplayerRoom> multiplayerRoom = APIHelper.getMultiplayerRoom(osuToken.accessToken());
            target = new RoomTarget(multiplayerRoom.getContent().getId(), MultiplayerRoomVersion.LAZER);
        } else {
            String version = startArgumentCount == 2 ? ctx.argument(argumentOffset + 1) : null;
            target = parseRoomTarget(ctx.argument(argumentOffset), version);
        }

        if (target == null) {
            ctx.sendReply(PendingMessage.ofString("房间 ID、链接或版本格式不正确。\n" + USAGE));
            return;
        }

        taskCoordinator.runApiRequest(ctx, "Start Multiplayer Room Watch", () -> {
            if (!ctx.sendMessage(PendingMessage.ofString("正在尝试启动多人房间监视……")).success()) {
                ctx.sendReply(PendingMessage.ofString(
                        "由于缺少主动消息权限，无法启动监视！权限配置请见：https://docs.seira.top/overview/use.html#extra-permission"
                ));
                return;
            }
            try {
                RoomWatchView view = watchService.watch(
                        ctx.groupId(), ctx.senderUserId(), target.version(), target.roomId()
                );
                ctx.sendReply(PendingMessage.ofMarkdownRaw(
                        "已开始监视 `" + formatRoom(view) + "` 。"
                                + "之后完成的每张图都会自动推送结果。"
                ));
            } catch (IllegalArgumentException | IllegalStateException e) {
                throw new ResolutionException(e.getMessage());
            }
        });
    }

    private void handleStop(Context ctx) {
        if (ctx.argumentCount() == 2 && "all".equalsIgnoreCase(ctx.argument(1))) {
            int stoppedCount = watchService.stopAll(ctx.groupId()).size();
            ctx.sendReply(PendingMessage.ofString(
                    stoppedCount == 0
                            ? "当前群聊没有多人房间监视。"
                            : "已停止当前群聊的全部 " + stoppedCount + " 个多人房间监视。"
            ));
            return;
        }
        if (ctx.argumentCount() != 1) {
            usage(ctx);
            return;
        }
        RoomWatchView stopped = watchService.stop(ctx.groupId(), ctx.senderUserId());
        ctx.sendReply(PendingMessage.ofString(
                stopped == null
                        ? "你当前没有在本群启动多人房间监视。"
                        : "已停止你启动的监视：" + formatRoom(stopped) + "。"
        ));
    }

    private void handleStatus(Context ctx) {
        if (ctx.argumentCount() != 1) {
            usage(ctx);
            return;
        }
        RoomWatchView view = watchService.get(ctx.groupId(), ctx.senderUserId());
        ctx.sendReply(PendingMessage.ofString(
                view == null
                        ? "你当前没有在本群启动多人房间监视。"
                        : "你当前正在监视" + formatRoom(view) + "。"
        ));
    }

    record RoomTarget(long roomId, MultiplayerRoomVersion version) {
    }
}
