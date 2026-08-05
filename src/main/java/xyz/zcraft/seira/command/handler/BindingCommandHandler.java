package xyz.zcraft.seira.command.handler;

import xyz.zcraft.seira.binding.BindingService;
import xyz.zcraft.seira.binding.UserDataStore;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.command.reply.ReplyFactory;
import xyz.zcraft.seira.command.route.RouteDecision;
import xyz.zcraft.seira.config.AppConfig;

public final class BindingCommandHandler {
    private final AppConfig config;
    private final ReplyFactory replyFactory;
    private final BindingService bindingService;

    public BindingCommandHandler(AppConfig config, ReplyFactory replyFactory, BindingService bindingService) {
        this.config = config;
        this.replyFactory = replyFactory;
        this.bindingService = bindingService;
    }

    public RouteDecision handleBind(Context ctx) {
        if (ctx.senderUserId() == null || ctx.senderUserId().isBlank()) {
            return RouteDecision.sync(PendingMessage.ofString("无法识别你的用户ID，暂时无法绑定。请稍后重试。"));
        }

        if (UserDataStore.findBoundUid(ctx.senderUserId()) != null) {
            return RouteDecision.sync(PendingMessage.ofString("你已经绑定了玩家ID，如果要更换绑定请先使用 /unbind 解绑当前玩家ID。"));
        }

        if (ctx.args().length != 0) {
            return RouteDecision.sync(PendingMessage.ofString("用法：/bind"));
        }

        final var bindingTask = bindingService.createBindingTask(ctx.senderUserId(), ctx.messageId(), (user, token) -> {
            UserDataStore.bind(ctx.senderUserId(), user.getId());
            UserDataStore.storeToken(ctx.senderUserId(), token);
            UserDataStore.storeUserInfo(user.getId(), user.getUsername());
        });

        return RouteDecision.sync(replyFactory.bindMessage(ctx, config.binding(), bindingTask,
                ctx.groupId() == null || ctx.groupId().isBlank()));
    }

    public RouteDecision handleUnbind(Context ctx) {
        if (ctx.senderUserId() == null || ctx.senderUserId().isBlank()) {
            return RouteDecision.sync(PendingMessage.ofString("无法识别你的用户ID，暂时无法解绑。请稍后重试。"));
        }
        if (ctx.args().length != 0) {
            return RouteDecision.sync(PendingMessage.ofString("用法：/unbind"));
        }
        boolean removed = UserDataStore.unbind(ctx.senderUserId());
        return RouteDecision.sync(PendingMessage.ofString(removed
                ? "解绑成功。"
                : "你当前还没有绑定玩家ID，无需解绑。"));
    }

    public RouteDecision handleClearHistory(Context ctx) {
        if (ctx.senderUserId() == null || ctx.senderUserId().isBlank()) {
            return RouteDecision.sync(PendingMessage.ofString("无法识别你的用户ID，无法清除历史记录。请稍后重试。"));
        }
        if (ctx.args().length != 0) {
            return RouteDecision.sync(PendingMessage.ofString("用法：/clearhistory"));
        }
        int removed = UserDataStore.clearGroupMember(ctx.senderUserId());
        return RouteDecision.sync(PendingMessage.ofString("清除了 " + removed + " 条群聊记录。"));
    }

}
