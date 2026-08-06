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
import xyz.zcraft.seira.bot.MessageSender;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.command.CommandHandler;
import xyz.zcraft.seira.command.TaskCoordinator;
import xyz.zcraft.seira.command.reply.ReplyFactory;
import xyz.zcraft.seira.config.AppConfig;
import xyz.zcraft.seira.util.OsuAuthHelper;

import java.io.ByteArrayOutputStream;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class DebugRoutes {
    private static final Logger LOG = LogManager.getLogger(DebugRoutes.class);
    private final Supplier<AppConfig> configSupplier;
    private final MessageSender messageSender;
    private final ReplyFactory replyFactory;
    private final TaskCoordinator taskCoordinator;
    private final OsuAuthHelper authHelper;
    private final Predicate<String> adminAuthorizer;
    private final CommandHandler unknownCommand;

    public DebugRoutes(
            Supplier<AppConfig> configSupplier,
            MessageSender messageSender,
            ReplyFactory replyFactory,
            TaskCoordinator taskCoordinator,
            OsuAuthHelper authHelper,
            Predicate<String> adminAuthorizer,
            CommandHandler unknownCommand
    ) {
        this.configSupplier = configSupplier;
        this.messageSender = messageSender;
        this.replyFactory = replyFactory;
        this.taskCoordinator = taskCoordinator;
        this.authHelper = authHelper;
        this.adminAuthorizer = adminAuthorizer;
        this.unknownCommand = unknownCommand;
    }

    public void routeDebug(Context ctx) {
        if (!configSupplier.get().seira().debugMode()) {
            ctx.sendReply(PendingMessage.ofString("未知指令。使用/help获取帮助。"));
            return;
        }

        if (!adminAuthorizer.test(ctx.senderUserId())) {
            ctx.sendReply(PendingMessage.ofString("你没有权限使用此指令。"));
            return;
        }

        switch (ctx.command()) {
            case "debug.upload" -> handleUpload(ctx);
            case "debug.test" -> handleTest(ctx);
            case "debug.message" -> handleMessage(ctx);
            case "debug.db" -> handleDb(ctx);
            case "debug.update-user-info" -> handleUpdateUserInfo(ctx);
            case "debug.get-all-friends" -> handleGetAllFriends(ctx);
            case "debug.validate-token" -> handleValidateToken(ctx);
            case "debug.active-message" -> handleActiveMessage(ctx);
            default -> unknownCommand.handle(ctx);
        }
    }

    private void handleActiveMessage(Context ctx) {
        if (ctx.inGroup()) {
            ctx.sendMessage(PendingMessage.ofString("111"));
        }
    }

    public void handleUpload(Context ctx) {
        if (ctx.argumentCount() != 3) {
            ctx.sendReply(PendingMessage.ofString("用法：/debug.upload <type> <cos> <url>"));
            return;
        }

        String typeStr = ctx.argument(0);
        String cosStr = ctx.argument(1);
        String urlStr = ctx.argument(2);

        FileInfo fileInfo;
        if (ctx.groupId() != null && !ctx.groupId().isBlank()) {
            fileInfo = messageSender.uploadGroupMedia(ctx.groupId(), Integer.parseInt(typeStr), urlStr, "true".equals(cosStr));
        } else {
            fileInfo = messageSender.uploadPrivateMedia(ctx.senderUserId(), Integer.parseInt(typeStr), urlStr, "true".equals(cosStr));
        }

        ctx.sendReply(PendingMessage.ofString(fileInfo != null
                ? "上传成功，fileId: " + fileInfo
                : "上传失败，请检查日志获取详情"));
    }

    public void handleTest(Context ctx) {
        ctx.sendReply(replyFactory.testMessage());
    }

    public void handleMessage(Context ctx) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            new Base64Encoder().decode(ctx.query(), out);
            ctx.sendReply(PendingMessage.ofMarkdownRaw(out.toString()));
        } catch (Exception e) {
            ctx.sendReply(PendingMessage.ofString("解码失败"));
        }
    }

    public void handleDb(Context ctx) {
        try {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(UserDataStore.executeQueryOrEdit(ctx.query())));
        } catch (Exception e) {
            Throwable cause = e;

            while (cause != null && !(cause instanceof SQLException)) {
                cause = cause.getCause();
            }

            if (cause != null) {
                ctx.sendReply(PendingMessage.ofString("执行失败: " + cause.getMessage()));
                return;
            }

            ctx.sendReply(PendingMessage.ofString("执行失败"));
        }
    }

    public void handleUpdateUserInfo(Context ctx) {
        try {
            final List<Long> allUsers = UserDataStore.findAllUsers();
            taskCoordinator.runApiRequest(ctx, "Update All User Info", () -> {
                APIHelper.getUsers(allUsers).forEach(user -> UserDataStore.storeUserInfo(user.getId(), user.getUsername()));
                ctx.sendReply(PendingMessage.ofString("更新完成，共更新了" + allUsers.size() + "个用户的信息"));
            });
        } catch (Exception e) {
            ctx.sendReply(PendingMessage.ofString("用户信息更新失败"));
        }
    }

    public void handleGetAllFriends(Context ctx) {
        taskCoordinator.runApiRequest(ctx, "Get All Friends", () -> {
            try {
                final List<OsuAuthHelper.TokenStore> allOsuTokens = UserDataStore.getAllOsuTokens();
                allOsuTokens
                        .stream()
                        .map(OsuAuthHelper.TokenStore::openId)
                        .map(authHelper::updateTokenAndGet)
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

                ctx.sendReply(PendingMessage.ofString("获取完成，共获取了" + allOsuTokens.size() + "个用户的好友列表"));
            } catch (Exception e) {
                LOG.error("Failed to get friends", e);
                ctx.sendReply(PendingMessage.ofString("用户信息更新失败"));
            }
        });
    }

    public void handleValidateToken(Context ctx) {
        taskCoordinator.runApiRequest(ctx, "Validate Token", () -> {
            int updated = 0, removed = 0;
            try {
                final List<OsuAuthHelper.TokenStore> allOsuTokens = UserDataStore.getAllOsuTokens();
                for (var token : allOsuTokens) {
                    if (token.osuToken().isExpired()) {
                        final OsuToken newToken = authHelper.updateTokenAndGet(token.openId());
                        if (newToken == null) {
                            UserDataStore.removeToken(token.openId());
                            removed++;
                        } else {
                            updated++;
                        }
                    }
                }

                ctx.sendReply(PendingMessage.ofString("Token验证完成，共更新了" + updated + "，移除了" + removed));
            } catch (Exception e) {
                LOG.error("Failed to get friends", e);
                ctx.sendReply(PendingMessage.ofString("用户信息更新失败"));
            }
        });
    }
}
