package xyz.zcraft.seira.ai;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import xyz.zcraft.seira.bot.data.GroupBotState;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.command.parse.Resolver;
import xyz.zcraft.seira.db.UserDataStore;
import xyz.zcraft.seira.services.AiPermission;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import static xyz.zcraft.seira.command.reply.ReplyFactory.at;

public class AiChatHandler {
    private static final Gson GSON = new Gson();
    private final Resolver resolver;
    private final Predicate<String> adminAuthorizer;
    private final AgentService agentService;
    private final Function<String, GroupBotState> botStateGetter;

    public AiChatHandler(
            Resolver resolver, AgentService agentService, Predicate<String> isAdmin,
            Function<String, GroupBotState> botStateGetter
    ) {
        this.resolver = resolver;
        this.adminAuthorizer = isAdmin;
        this.agentService = agentService;
        this.botStateGetter = botStateGetter;
    }

    public void handleAi(Context ctx) {
        if (!ctx.inGroup()) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "AI对话仅在群组中可用喵。"));
            return;
        }

        if (ctx.argumentCount() == 0) {
            final boolean b = AiPermission.doPermit(ctx.groupId());
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "目前AI对话在本群启用状态为：" + (b ? "√" : "×")));
            return;
        } else if (ctx.argumentCount() == 1
                && List.of("on", "off", "reset").contains(ctx.argument(0).toLowerCase(Locale.ROOT))) {
            if (!adminAuthorizer.test(ctx.senderUserId())) {
                ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "你无权使用该命令喵。\n" +
                        "> 由于此功能开销较大、处于测试阶段且较为不可控，暂未开放。若想要在此群中使用此功能，请联系 Bot 管理员喵。"));
                return;
            }

            if ("on".equalsIgnoreCase(ctx.argument(0))) {
                final GroupBotState apply = botStateGetter.apply(ctx.groupId());
                if (apply.allowProactiveMsg() && apply.receiveMsgSetting() == GroupBotState.ReceiveMsgSetting.ALL) {
                    AiPermission.permit(ctx.groupId());
                    ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "已启用本群AI对话喵。"));
                } else {
                    ctx.sendReply(PendingMessage.ofMarkdownRaw(
                            at(ctx) + "由于本群未配置权限或配置不完整，暂无法启用本群AI对话喵。" +
                                    "权限配置见[这里](https://docs.seira.top/overview/use.html#extra-permission)~")
                    );
                }
                return;
            } else if ("off".equalsIgnoreCase(ctx.argument(0))) {
                AiPermission.revoke(ctx.groupId());
                ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "已禁用本群AI对话喵。"));
                return;
            } else if ("reset".equalsIgnoreCase(ctx.argument(0))) {
                final int i = agentService.clearStateOfGroup(ctx.groupId());
                ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "已重置本群" + i + "个用户的AI对话状态喵。"));
                return;
            }
        }

        ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "用法：/ai [on|off]"));
    }

    public void handleChat(Context ctx) {
        if (agentService.isRunning(ctx.groupId(), ctx.senderUserId())) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "已有一轮对话正在进行中了喵，请稍作等待~"));
            return;
        }

        JsonObject qqContext = new JsonObject();
        qqContext.addProperty("in_group", ctx.inGroup());
        qqContext.addProperty("sender_open_id", ctx.senderUserId());
        qqContext.addProperty("group_id", ctx.groupId());

        Map<String, Long> bindings = new HashMap<>();
        Map<Long, String> usernames = new HashMap<>();

        final List<String> ids = resolver.extractAllMentionedIds(ctx.rawContent());
        ids.add(ctx.senderUserId());

        for (String openId : ids) {
            final Long uid = resolver.resolveBoundUid(openId);
            if (uid != null) {
                bindings.put(openId, uid);
                UserDataStore.findUsername(uid).ifPresent(s -> usernames.put(uid, s));
            }
        }

        qqContext.add("bindings", GSON.toJsonTree(bindings));
        qqContext.add("usernames", GSON.toJsonTree(usernames));

        final String answer = at(ctx) + agentService.input(
                ctx.groupId(),
                ctx.senderUserId(),
                ctx.senderUserId() + ": " + ctx.rawContent(),
                var -> var.put("CONTEXT", qqContext.toString())
        );

        if (!ctx.sendReply(PendingMessage.ofMarkdownRaw(answer)).success()) {
            if (!ctx.sendMessage(PendingMessage.ofMarkdownRaw(answer)).success()) {
                ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "消息发送失败了喵。"));
            }
        }
    }
}
