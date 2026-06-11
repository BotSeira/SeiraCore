package xyz.zcraft.seira.command.route;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.util.encoders.Base64Encoder;
import xyz.zcraft.osu.model.UserExtended;
import xyz.zcraft.seira.api.APIHelper;
import xyz.zcraft.seira.api.data.FriendEntry;
import xyz.zcraft.seira.api.data.OsuToken;
import xyz.zcraft.seira.api.data.Response;
import xyz.zcraft.seira.binding.UserDataStore;
import xyz.zcraft.seira.bot.data.FileInfo;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.command.RouteDecision;
import xyz.zcraft.seira.command.Router;
import xyz.zcraft.seira.util.OsuAuthHelper;

import java.io.ByteArrayOutputStream;
import java.sql.SQLException;
import java.util.List;

public class DebugRoutes {
    private static final Logger LOG = LogManager.getLogger(DebugRoutes.class);
    private final Router router;

    public DebugRoutes(Router router) {
        this.router = router;
    }

    public RouteDecision routeDebug(Context ctx) {
        if (!router.config.seira().debugMode()) {
            return RouteDecision.sync(PendingMessage.ofString("未知指令。使用/help获取帮助。"));
        }

        if (!router.isAdmin(ctx.senderUserId())) {
            return RouteDecision.sync(PendingMessage.ofString("你没有权限使用此指令。"));
        }

        return switch (ctx.command()) {
            case "debug.upload" -> handleUpload(ctx);
            case "debug.test" -> handleTest();
            case "debug.message" -> handleMessage(ctx);
            case "debug.db" -> handleDb(ctx);
            case "debug.update-user-info" -> handleUpdateUserInfo(ctx);
            case "debug.get-all-friends" -> handleGetAllFriends(ctx);
            case "debug.validate-token" -> handleValidateToken(ctx);
            default -> router.handleUnknown();
        };
    }

    public RouteDecision handleUpload(Context ctx) {
        if (ctx.args().length != 3) {
            return RouteDecision.sync(PendingMessage.ofString("用法：/debug.upload <type> <cos> <url>"));
        }

        String typeStr = ctx.args()[0];
        String cosStr = ctx.args()[1];
        String urlStr = ctx.args()[2];

        FileInfo fileInfo;
        if (ctx.groupId() != null && !ctx.groupId().isBlank()) {
            fileInfo = router.messageSender.uploadGroupMedia(ctx.groupId(), Integer.parseInt(typeStr), urlStr, "true".equals(cosStr));
        } else {
            fileInfo = router.messageSender.uploadPrivateMedia(ctx.senderUserId(), Integer.parseInt(typeStr), urlStr, "true".equals(cosStr));
        }

        return RouteDecision.sync(PendingMessage.ofString(fileInfo != null
                ? "上传成功，fileId: " + fileInfo
                : "上传失败，请检查日志获取详情"));
    }

    public RouteDecision handleTest() {
        return RouteDecision.sync(router.replyFactory.testMessage());
    }

    public RouteDecision handleMessage(Context ctx) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            new Base64Encoder().decode(ctx.query(), out);
            return RouteDecision.sync(PendingMessage.ofMarkdownRaw(out.toString()));
        } catch (Exception e) {
            return RouteDecision.sync(PendingMessage.ofString("解码失败"));
        }
    }

    public RouteDecision handleDb(Context ctx) {
        try {
            return RouteDecision.sync(PendingMessage.ofMarkdownRaw(UserDataStore.executeQueryOrEdit(ctx.query())));
        } catch (Exception e) {
            Throwable cause = e;

            while (cause != null && !(cause instanceof SQLException)) {
                cause = cause.getCause();
            }

            if (cause != null) {
                return RouteDecision.sync(
                        PendingMessage.ofString("执行失败: " + cause.getMessage())
                );
            }

            return RouteDecision.sync(
                    PendingMessage.ofString("执行失败")
            );
        }
    }

    public RouteDecision handleUpdateUserInfo(Context ctx) {
        try {
            final List<Long> allUsers = UserDataStore.findAllUsers();
            return router.taskCoordinator.queueApiRequest(ctx, "Update All User Info", () -> {
                APIHelper.getUsers(allUsers).forEach(user -> UserDataStore.storeUserInfo(user.getId(), user.getUsername()));
                return PendingMessage.ofString("更新完成，共更新了" + allUsers.size() + "个用户的信息");
            });
        } catch (Exception e) {
            return RouteDecision.sync(PendingMessage.ofString("用户信息更新失败"));
        }
    }

    public RouteDecision handleGetAllFriends(Context ctx) {
        return router.taskCoordinator.queueApiRequest(ctx, "Get All Friends", () -> {
            try {
                final List<OsuAuthHelper.TokenStore> allOsuTokens = UserDataStore.getAllOsuTokens();
                allOsuTokens
                        .stream()
                        .map(OsuAuthHelper.TokenStore::openId)
                        .map(router.authHelper::updateTokenAndGet)
                        .map(OsuToken::accessToken)
                        .forEach(accessToken -> {
                            final Response<UserExtended> self = APIHelper.getSelf(accessToken);
                            final Response<List<FriendEntry>> response = APIHelper.getFollowed(accessToken);
                            final List<FriendEntry> content = response.getContent();
                            final List<Long> ids = content.stream().map(e -> e.user().getId()).toList();

                            final long uid = self.getContent().getId();

                            UserDataStore.storeUserInfo(uid, self.getContent().getUsername());
                            response.getContent().stream()
                                    .map(FriendEntry::user)
                                    .forEach(u -> UserDataStore.storeUserInfo(u.getId(), u.getUsername()));

                            final List<Long> origFollower = UserDataStore.findFollower(uid);

                            origFollower.stream()
                                    .filter(i -> !ids.contains(i))
                                    .forEach(i -> UserDataStore.removeFollowed(uid, i));

                            for (FriendEntry friendEntry : content) {
                                if (!UserDataStore.haveFollowed(uid, friendEntry.user().getId())) {
                                    UserDataStore.storeFollowed(uid, friendEntry.user().getId());
                                }

                                if (friendEntry.mutual()) {
                                    if (!UserDataStore.haveFollowed(friendEntry.user().getId(), uid)) {
                                        UserDataStore.storeFollowed(friendEntry.user().getId(), uid);
                                    }
                                }
                            }
                        });

                return PendingMessage.ofString("获取完成，共获取了" + allOsuTokens.size() + "个用户的好友列表");
            } catch (Exception e) {
                LOG.error("Failed to get friends", e);
                return PendingMessage.ofString("用户信息更新失败");
            }
        });
    }

    public RouteDecision handleValidateToken(Context ctx) {
        return router.taskCoordinator.queueApiRequest(ctx, "Validate Token", () -> {
            int updated = 0, removed = 0;
            try {
                final List<OsuAuthHelper.TokenStore> allOsuTokens = UserDataStore.getAllOsuTokens();
                for (var token : allOsuTokens) {
                    if (token.osuToken().isExpired()) {
                        final OsuToken newToken = router.authHelper.updateTokenAndGet(token.openId());
                        if (newToken == null) {
                            UserDataStore.removeToken(token.openId());
                            removed++;
                        } else {
                            updated++;
                        }
                    }
                }

                return PendingMessage.ofString("Token验证完成，共更新了" + updated + "，移除了" + removed);
            } catch (Exception e) {
                LOG.error("Failed to get friends", e);
                return PendingMessage.ofString("用户信息更新失败");
            }
        });
    }
}
