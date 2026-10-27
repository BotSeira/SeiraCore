package xyz.zcraft.seira.command.handler;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.seira.api.AsteroidApi;
import xyz.zcraft.seira.api.data.MinecraftServerStatus;
import xyz.zcraft.seira.bot.MessageSender;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.command.TargetHistory;
import xyz.zcraft.seira.command.TaskCoordinator;
import xyz.zcraft.seira.command.parse.Resolver;
import xyz.zcraft.seira.command.parse.TargetResolver;
import xyz.zcraft.seira.command.reply.ReplyFactory;
import xyz.zcraft.seira.services.McMessageService;

import java.util.function.Predicate;
import java.util.regex.Pattern;

import static xyz.zcraft.seira.command.reply.ReplyFactory.at;

public class MinecraftCommandHandler {
    private static final Pattern SERVER_PATTERN = Pattern.compile("^(?:https?://)?([a-zA-Z0-9-]+(?:\\.[a-zA-Z0-9-]+)+)(?::[0-9]+)?$");
    private static final Logger LOG = LogManager.getLogger(MinecraftCommandHandler.class);
    private final MessageSender messageSender;
    private final TargetResolver targets;
    private final TargetHistory history;
    private final TaskCoordinator taskCoordinator;
    private final ReplyFactory replyFactory;
    private final Resolver resolver;
    private final Predicate<String> adminAuthorizer;
    private final McMessageService mcMessageService;

    public MinecraftCommandHandler(
            MessageSender messageSender,
            TaskCoordinator taskCoordinator,
            ReplyFactory replyFactory,
            Resolver resolver,
            TargetResolver targets,
            TargetHistory history,
            Predicate<String> adminAuthorizer,
            McMessageService mcMessageService
    ) {
        this.messageSender = messageSender;
        this.taskCoordinator = taskCoordinator;
        this.replyFactory = replyFactory;
        this.resolver = resolver;
        this.targets = targets;
        this.history = history;
        this.adminAuthorizer = adminAuthorizer;
        this.mcMessageService = mcMessageService;
    }

    public void handleMc(Context ctx) {
        if (ctx.argumentCount() != 1) {
            ctx.sendReply(at(ctx) + "用法：/mc <服务器地址>");
            return;
        }

        try {
            final String address = ctx.argument(0);

            if (!SERVER_PATTERN.matcher(address).matches()) {
                ctx.sendReply(at(ctx) + "服务器地址无效喵。");
                return;
            }

            final var probe = AsteroidApi.getMinecraftServerStatus(address);

            final var status = probe.status();
            final var players = status.players();
            final var samples = players.sample();

            String playersSample = "";

            if (samples != null) {
                playersSample = String.join(", ", samples.stream().map(MinecraftServerStatus.Players.Sample::name).toList());
            }

            ctx.sendReply(at(ctx) + """
                             `%s` 的服务器状态：
                             - 版本: `%s`
                             - 描述: `%s`
                             - 延迟: `%d` ms
                             - 在线人数: `%d` / `%d`
                             - 在线玩家: [%s]
                            """.formatted(
                            address,
                            status.version().name(),
                            status.description(),
                            probe.latency(),
                            players.online(),
                            players.max(),
                            playersSample
                    )
            );
        } catch (Exception e) {
            ctx.sendReply(at(ctx) + "无法获取目标服务器状态喵，这可能是因为目标服务器未开启或者存在网络问题。");
            LOG.warn("Failed to probe Minecraft server", e);
        }
    }

    public void handleMcBind(Context ctx) {
        if (ctx.argumentCount() != 0) {
            ctx.sendReply(at(ctx) + "用法：/mcbind");
            return;
        }

        if (!ctx.inGroup()) {
            ctx.sendReply(at(ctx) + "此命令只能在群聊中使用。");
            return;
        }

        final String code = mcMessageService.createBindingTask(ctx.groupId());

        ctx.sendReply(at(ctx) + "请在 Minecraft 中输入以下指令以绑定服务器：`/chatforwarder bind " + code + "`");
    }
}
