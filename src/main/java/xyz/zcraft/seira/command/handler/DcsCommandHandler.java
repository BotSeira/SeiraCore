package xyz.zcraft.seira.command.handler;

import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.discord.DcsTarget;
import xyz.zcraft.seira.discord.DiscordBridgeService;

import java.util.Locale;
import java.util.Objects;

public final class DcsCommandHandler {
    private static final String USAGE = "用法：/dcs start <guild-id>.<channel-id>，或 /dcs stop";

    private final DiscordBridgeService bridgeService;

    public DcsCommandHandler(DiscordBridgeService bridgeService) {
        this.bridgeService = Objects.requireNonNull(bridgeService);
    }

    public void handleDcs(Context ctx) {
        if (!ctx.inGroup()) {
            ctx.sendReply(PendingMessage.ofString("/dcs 仅支持在群聊中使用。"));
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
        if (ctx.argumentCount() != 2) {
            usage(ctx);
            return;
        }
        DcsTarget target = DcsTarget.parse(ctx.argument(1));
        if (target == null) {
            ctx.sendReply(PendingMessage.ofString("Discord 目标格式无效，应为 <guild-id>.<channel-id>。"));
            return;
        }

        final boolean b = ctx.sendMessage(PendingMessage.ofString("正在尝试开启 Discord 消息同步，请稍候..."));
        if (!b) {
            ctx.sendReply(PendingMessage.ofString("由于缺少主动消息权限，无法添加消息同步！权限配置请见[这里](https://docs.seira.top/overview/use.html#extra-permission)~"));
            return;
        }

        DiscordBridgeService.BindResult result = bridgeService.bind(ctx.groupId(), target);
        if (!result.success()) {
            ctx.sendReply(PendingMessage.ofString("开启 Discord 同步失败：" + result.message()));
            return;
        }
        ctx.sendReply(PendingMessage.ofString(
                "Discord 消息同步已开启：" + result.guildName() + " / #" + result.channelName()
        ));
    }

    private void handleStop(Context ctx) {
        if (ctx.argumentCount() != 1) {
            usage(ctx);
            return;
        }
        boolean removed = bridgeService.unbind(ctx.groupId());
        ctx.sendReply(PendingMessage.ofString(
                removed ? "Discord 消息同步已解除。" : "当前群聊尚未开启 Discord 消息同步。"
        ));
    }

    private static void usage(Context ctx) {
        ctx.sendReply(PendingMessage.ofString(USAGE));
    }
}
