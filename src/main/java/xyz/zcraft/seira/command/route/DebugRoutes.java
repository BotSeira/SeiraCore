package xyz.zcraft.seira.command.route;

import org.bouncycastle.util.encoders.Base64Encoder;
import xyz.zcraft.seira.api.APIHelper;
import xyz.zcraft.seira.binding.UserDataStore;
import xyz.zcraft.seira.bot.data.FileInfo;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.command.RouteDecision;
import xyz.zcraft.seira.command.Router;

import java.io.ByteArrayOutputStream;
import java.util.List;

public class DebugRoutes {
    private final Router router;

    public DebugRoutes(Router router) {
        this.router = router;
    }

    public RouteDecision handleDebugUpload(Context ctx) {
        if (!router.config.seira().debugMode()) {
            return RouteDecision.sync(PendingMessage.ofString("未知指令。使用/help获取帮助。"));
        }

        if (!router.isAdmin(ctx.senderUserId())) {
            return RouteDecision.sync(PendingMessage.ofString("你没有权限使用此指令。"));
        }

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

    public RouteDecision handleDebugTest(Context ctx) {
        if (!router.config.seira().debugMode()) {
            return RouteDecision.sync(PendingMessage.ofString("未知指令。使用/help获取帮助。"));
        }

        if (!router.isAdmin(ctx.senderUserId())) {
            return RouteDecision.sync(PendingMessage.ofString("你没有权限使用此指令。"));
        }

        return RouteDecision.sync(router.replyFactory.testMessage());
    }

    public RouteDecision handleDebugMessage(Context ctx) {
        if (!router.config.seira().debugMode()) {
            return RouteDecision.sync(PendingMessage.ofString("未知指令。使用/help获取帮助。"));
        }

        if (!router.isAdmin(ctx.senderUserId())) {
            return RouteDecision.sync(PendingMessage.ofString("你没有权限使用此指令。"));
        }

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            new Base64Encoder().decode(ctx.query(), out);
            return RouteDecision.sync(PendingMessage.ofMarkdownRaw(out.toString()));
        } catch (Exception e) {
            return RouteDecision.sync(PendingMessage.ofString("解码失败"));
        }
    }

    public RouteDecision handleDebugImage(Context ctx) {
        if (!router.config.seira().debugMode()) {
            return RouteDecision.sync(PendingMessage.ofString("未知指令。使用/help获取帮助。"));
        }

        if (!router.isAdmin(ctx.senderUserId())) {
            return RouteDecision.sync(PendingMessage.ofString("你没有权限使用此指令。"));
        }

        try {
            return RouteDecision.sync(PendingMessage.ofImageBase64(ctx.query()));
        } catch (Exception e) {
            return RouteDecision.sync(PendingMessage.ofString("解码失败"));
        }
    }

    public RouteDecision handleDebugDb(Context ctx) {
        if (!router.config.seira().debugMode()) {
            return RouteDecision.sync(PendingMessage.ofString("未知指令。使用/help获取帮助。"));
        }

        if (!router.isAdmin(ctx.senderUserId())) {
            return RouteDecision.sync(PendingMessage.ofString("你没有权限使用此指令。"));
        }

        try {
            return RouteDecision.sync(PendingMessage.ofString(UserDataStore.executeQueryOrEdit(ctx.query())));
        } catch (Exception e) {
            return RouteDecision.sync(PendingMessage.ofString("解码失败"));
        }
    }

    public RouteDecision handleDebugUpdateUserInfo(Context ctx) {
        if (!router.config.seira().debugMode()) {
            return RouteDecision.sync(PendingMessage.ofString("未知指令。使用/help获取帮助。"));
        }

        if (!router.isAdmin(ctx.senderUserId())) {
            return RouteDecision.sync(PendingMessage.ofString("你没有权限使用此指令。"));
        }

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

}
