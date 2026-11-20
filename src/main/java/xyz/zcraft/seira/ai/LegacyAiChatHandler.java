package xyz.zcraft.seira.ai;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import xyz.zcraft.seira.ai.data.AgentFile;
import xyz.zcraft.seira.ai.provider.ChatProvider;
import xyz.zcraft.seira.bot.MessageSender;
import xyz.zcraft.seira.bot.data.Attachment;
import xyz.zcraft.seira.bot.data.GroupBotState;
import xyz.zcraft.seira.bot.data.MsgElem;
import xyz.zcraft.seira.bot.data.msg.PendingMessage;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.command.parse.Resolver;
import xyz.zcraft.seira.db.UserDataStore;
import xyz.zcraft.seira.services.AiPermission;
import xyz.zcraft.seira.util.ResourceUtil;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static xyz.zcraft.seira.command.reply.ReplyFactory.ExternalUrls.CHANNEL;
import static xyz.zcraft.seira.command.reply.ReplyFactory.ExternalUrls.PERMISSION;
import static xyz.zcraft.seira.command.reply.ReplyFactory.at;
import static xyz.zcraft.seira.command.reply.ReplyFactory.cmd;

@SuppressWarnings("DeprecatedIsStillUsed")
@Deprecated
public class LegacyAiChatHandler {
    private static final Gson GSON = new Gson();
    private static final Pattern CHAT_INLINE_FILE = Pattern.compile("%%\\[file:(.+?)]%%");
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Resolver resolver;
    private final Predicate<String> adminAuthorizer;
    private final ChatProvider chatProvider;
    private final Function<String, GroupBotState> botStateGetter;
    private final Map<String, Deque<String>> groupMentionedHistory = new ConcurrentHashMap<>();
    private final Set<String> consented = new HashSet<>();
    private final Map<String, Map<String, String>> groupNicknames = new ConcurrentHashMap<>();
    private final MessageSender messageSender;

    public LegacyAiChatHandler(
            Resolver resolver, ChatProvider chatProvider, Predicate<String> isAdmin,
            Function<String, GroupBotState> botStateGetter, MessageSender messageSender
    ) {
        this.resolver = resolver;
        this.adminAuthorizer = isAdmin;
        this.chatProvider = chatProvider;
        this.botStateGetter = botStateGetter;
        this.messageSender = messageSender;
    }

    public void recordNickname(String groupId, String openId, String nickname) {
        groupNicknames.computeIfAbsent(groupId, _ -> new ConcurrentHashMap<>());
        groupNicknames.get(groupId).put(openId, nickname);
    }

    public void handleAi(Context ctx) {
        if (!ctx.inGroup()) {
            ctx.sendReply(at(ctx) + "AI对话仅在群组中可用喵。");
            return;
        }

        if (ctx.argumentCount() == 0) {
            final boolean b = AiPermission.permits(ctx.groupId());
            final boolean c = AiPermission.isActivated(ctx.groupId());
            ctx.sendReply(at(ctx) + "目前AI对话在本群状态\n" +
                    "> 已授权：" + (b ? "√" : "×") + "\n" +
                    "> 已启用：" + (c ? "√" : "×")
            );
            return;
        } else if (ctx.argumentCount() == 1
                && List.of("on", "off", "grant", "revoke", "reset", "stop").contains(ctx.argument(0).toLowerCase(Locale.ROOT))) {
            if ("on".equalsIgnoreCase(ctx.argument(0))) {
                final GroupBotState apply = botStateGetter.apply(ctx.groupId());
                if (apply.allowProactiveMsg() && apply.receiveMsgSetting() == GroupBotState.ReceiveMsgSetting.ALL) {
                    if (AiPermission.isActivated(ctx.groupId())) {
                        ctx.sendReply(at(ctx) + "已启用本群AI已为启用状态喵。");
                    } else if (!consented.contains(ctx.groupId())) {
                        final String terms = ResourceUtil.loadString("/ai-chat-terms.md").orElse("文件加载失败了喵");
                        ctx.sendReply("> 使用 AI 对话功能即表示群内成员同意并遵守以下条款：\n\n" + terms + "\n\n> 本消息仅展示一次，再次使用 `/ai on` 即可开启 AI 对话。");
                        consented.add(ctx.groupId());
                    } else if (AiPermission.permits(ctx.groupId())) {
                        AiPermission.activate(ctx.groupId());
                        ctx.sendReply(at(ctx) + "已启用本群AI对话喵。");
                    } else {
                        if (adminAuthorizer.test(ctx.senderUserId())) {
                            ctx.sendReply(at(ctx) + "已授权并启用本群AI对话喵。");
                        } else {
                            ctx.sendReply(at(ctx) + "本群无此功能权限，请 [联系 Bot 管理员](%s) 喵。".formatted(CHANNEL));
                        }
                    }
                } else {
                    ctx.sendReply(PendingMessage.ofMarkdownRaw(
                            at(ctx) + "由于本群群主未配置权限或配置不完整，暂无法启用本群AI对话喵。" +
                                    "权限配置__群主__见[这里](" + PERMISSION + ")~")
                    );
                }
                return;
            } else if ("off".equalsIgnoreCase(ctx.argument(0))) {
                if (AiPermission.isActivated(ctx.groupId())) {
                    AiPermission.deactivate(ctx.groupId());
                    ctx.sendReply(at(ctx) + "已禁用本群AI对话喵。");
                } else {
                    ctx.sendReply(at(ctx) + "本群AI对话还未启用喵。");
                }
                return;
            } else if ("grant".equalsIgnoreCase(ctx.argument(0))) {
                if (adminAuthorizer.test(ctx.senderUserId())) {
                    AiPermission.grant(ctx.groupId());
                    ctx.sendReply(at(ctx) + "已授予本群AI对话权限喵。");
                } else {
                    ctx.sendReply(at(ctx) + "你无权使用该命令喵。");
                }
                return;
            } else if ("revoke".equalsIgnoreCase(ctx.argument(0))) {
                if (adminAuthorizer.test(ctx.senderUserId())) {
                    AiPermission.revoke(ctx.groupId());
                    if (AiPermission.isActivated(ctx.groupId())) {
                        ctx.sendReply(at(ctx) + "已停用并取消本群AI对话权限喵。");
                    } else {
                        ctx.sendReply(at(ctx) + "已撤销本群AI对话权限喵。");
                    }
                } else {
                    ctx.sendReply(at(ctx) + "你无权使用该命令喵。");
                }
                return;
            } else if ("reset".equalsIgnoreCase(ctx.argument(0))) {
                chatProvider.clearState(ctx.groupId(), ctx.senderUserId());
                ctx.sendReply(at(ctx) + "已重置你在本群的AI对话状态喵。");
                return;
            } else if ("stop".equalsIgnoreCase(ctx.argument(0))) {
                final ChatProvider.StopStatus stopStatus = chatProvider.requireStop(ctx.groupId(), ctx.senderUserId());
                ctx.sendReply(at(ctx) + switch (stopStatus) {
                    case SUCCESS -> "已停止你在本群的AI对话喵。";
                    case FAILED -> "停止AI对话失败了喵。";
                    case NO_CONVERSATION -> "目前没有运行中的对话喵。";
                    case NOT_SUPPORTED -> "当前不支持停止AI对话。";
                });

                return;
            }
        } else if (ctx.argumentCount() == 2 && ctx.argument(0).equalsIgnoreCase("reset")) {
            if ("group".equalsIgnoreCase(ctx.argument(1))) {
                if (!adminAuthorizer.test(ctx.senderUserId())) {
                    ctx.sendReply(at(ctx) + "你无权使用该命令喵。");
                    return;
                }
                final int i = chatProvider.clearStateOfGroup(ctx.groupId());
                ctx.sendReply(at(ctx) + "已重置本群" + i + "个用户的AI对话状态喵。");
                return;
            } else if ("all".equalsIgnoreCase(ctx.argument(1))) {
                final int i = chatProvider.clearStateOfUser(ctx.senderUserId());
                ctx.sendReply(at(ctx) + "已重置你在" + i + "个群中的AI对话状态喵。");
                return;
            }

        }

        ctx.sendReply(at(ctx) + "用法：/ai [on|off|reset|stop]");
    }

    public void handleChat(Context ctx, String message, List<MsgElem> elems) {
        if (!AiPermission.isActivated(ctx.groupId())) {
            return;
        }

        final String at = at(ctx);
        if (chatProvider.isRunning(ctx.groupId(), ctx.senderUserId())) {
            ctx.sendReply(
                    at + "你已有一轮对话正在进行中了喵，请稍作等待或" + cmd("/ai stop", "取消对话") + "~"
            );
            return;
        }


        final List<AgentFile> attachments = elems.stream()
                .filter(e -> e.attachments() != null)
                .flatMap(e -> e.attachments().stream())
                .map(e -> new AgentFile(e.filename(), null, e.size(), e.url()))
                .filter(AgentFile::isValid)
                .toList();

        final var refContent = elems.stream()
                .filter(e -> e.content() != null && !e.content().isBlank())
                .findFirst()
                .map(MsgElem::content)
                .orElse(null);

        chatProvider.input(
                ctx.groupId(),
                ctx.senderNickname(),
                ctx.senderUserId(),
                message,
                input -> generateVar(ctx, input),
                new StreamHandler() {
                    @Override
                    public void onText(String message) {
                        AtomicInteger filesToUpload = new AtomicInteger();
                        CHAT_INLINE_FILE.matcher(message).results()
                                .forEach(r -> {
                                    if (r.group(1) == null || r.group(1).isBlank()) return;
                                    final String fileUrl = r.group(1);
                                    filesToUpload.getAndIncrement();
                                    executor.submit(() -> {
                                        var fileInfo = ctx.inGroup()
                                                ? messageSender.uploadGroupMediaByParts(ctx.groupId(), fileUrl)
                                                : messageSender.uploadPrivateMediaByParts(ctx.senderUserId(), fileUrl);
                                        ctx.send(true, PendingMessage.ofUploadedFile(fileInfo, fileUrl));
                                    });
                                });

                        message = CHAT_INLINE_FILE.matcher(message).replaceAll("");
                        if (!message.trim().startsWith(at.trim())) {
                            message = at + message;
                        }

                        if (filesToUpload.get() > 0) {
                            message += "\n\n> " + filesToUpload.get() + " 个附件上传中...";
                        }

                        ctx.send(true, PendingMessage.ofMarkdownRaw(message.trim()), true);
                    }

                    @Override
                    public void onComplete(String fullText) {
                        // Do nothing
                    }

                    @Override
                    public void onError(String errorCode, String errorMsg) {
                        ctx.send(
                                true,
                                PendingMessage.ofMarkdownRaw(
                                        at + "回复生成失败了喵。\n" +
                                                "> " + errorCode + ": " + errorMsg + "\n" +
                                                "> 若重复出现错误，请尝试" + cmd("/ai reset", "重置会话")
                                ),
                                true
                        );
                    }
                },
                attachments,
                refContent
        );
    }

    private String generateVar(Context ctx, String input) {
        JsonObject qqContext = new JsonObject();
        qqContext.addProperty("in_group", ctx.inGroup());
        qqContext.addProperty("sender_nickname", ctx.senderNickname());
        qqContext.addProperty("sender_open_id", ctx.senderUserId());
        qqContext.addProperty("sender_osu_uid", resolver.resolveBoundUid(ctx.senderUserId()));
        qqContext.addProperty("group_id", ctx.groupId());

        Map<String, JsonObject> bindings = new HashMap<>();

        final Deque<String> mentionedHistory = groupMentionedHistory.computeIfAbsent(ctx.groupId(), _ -> new ArrayDeque<>(100));

        final Set<String> ids = resolver.extractAllMentionedIds(input);

        final var bound = UserDataStore.findBoundUsersByGroup(ctx.groupId()).entrySet().iterator();

        while (ids.size() < 50 && bound.hasNext()) {
            ids.add(bound.next().getKey());
        }

        final var uids = UserDataStore.findAllGroupMembers(ctx.groupId()).iterator();

        while (ids.size() < 50 && uids.hasNext()) {
            ids.add(uids.next());
        }

        for (String id : ids) {
            if (!mentionedHistory.contains(id)) {
                mentionedHistory.push(id);
            }
        }

        while (mentionedHistory.size() > 64) {
            mentionedHistory.removeFirst();
        }

        ids.add(ctx.senderUserId());
        ids.addAll(mentionedHistory);

        final Map<String, String> nicknames = groupNicknames.getOrDefault(ctx.groupId(), Map.of());

        for (String openId : ids) {
            final Long uid = resolver.resolveBoundUid(openId);
            if (uid != null) {
                final String username = UserDataStore.findUsername(uid).orElse("");
                JsonObject qqUser = new JsonObject();
                qqUser.addProperty("uid", uid.toString());
                qqUser.addProperty("username", username);
                if (nicknames.containsKey(openId)) {
                    qqUser.addProperty("qq_nickname", nicknames.get(openId));
                }
                bindings.put(openId, qqUser);
            }
        }

        qqContext.add("bindings", GSON.toJsonTree(bindings));

        return qqContext.toString();
    }

    public void recordHistory(String groupId, String username, String userId, String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            return;
        }

        recordHistory(groupId, username, userId, rawContent.trim(), List.of());
    }

    public void recordHistory(String groupId, String username, String userId, String rawContent, List<Attachment> attachments) {
        StringBuilder sb = new StringBuilder(rawContent.trim());
        if (attachments != null && !attachments.isEmpty()) {
            for (Attachment attachment : attachments) {
                sb.append("\n").append("![%s](%s)".formatted(attachment.filename(), attachment.url()));
            }
        }
        chatProvider.recordHistory(groupId, username, userId, sb.toString());
    }
}
