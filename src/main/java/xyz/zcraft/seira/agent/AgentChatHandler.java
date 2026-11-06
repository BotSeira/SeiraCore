package xyz.zcraft.seira.agent;

import xyz.zcraft.seira.bot.data.Attachment;
import xyz.zcraft.seira.bot.data.MsgElem;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.config.AgentConfig;
import xyz.zcraft.seira.services.AiPermission;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static xyz.zcraft.seira.command.reply.ReplyFactory.at;

public final class AgentChatHandler implements AutoCloseable {
    public static AtomicReference<AgentChatHandler> INSTANCE = new AtomicReference<>();
    private final Predicate<String> administrator;
    private final AgentConfig settings;
    private final ToolRegistry tools;
    private final AgentEngine engine;
    private volatile String prompt;
    public AgentChatHandler(Predicate<String> administrator, AgentConfig conf,
                            java.util.function.Function<String, String> osuToken,
                            xyz.zcraft.seira.config.OstellaConfig ostella,
                            xyz.zcraft.seira.config.AsteroidConfig asteroid) {
        this.administrator = administrator;
        tools = new ToolRegistry(administrator, this::enabled);

        try {
            settings = conf;

            if (settings.enabled()) {
                JavaTools.register(tools);
                ServiceToolDefinitions.register(new ServiceTools(tools, ostella, asteroid, osuToken));
                reload();
                engine = new AgentEngine(
                        new ResponsesClient(settings),
                        tools,
                        () -> prompt,
                        new ConversationStore(Path.of("data", "agent-conversations")),
                        this::enabled
                );
            } else {
                engine = null;
            }
        } catch (IOException e) {
            tools.close();
            throw new IllegalStateException("Cannot load agent configuration", e);
        }

        INSTANCE.set(this);
    }

    public static AgentChatHandler getInstance() {
        return INSTANCE.get();
    }

    private static void sendResponse(Context context, String message) {
        var matcher = java.util.regex.Pattern.compile("%%\\[file:([^\\r\\n]+?)]%%").matcher(message);
        var files = new java.util.LinkedHashSet<String>();
        StringBuilder markdown = new StringBuilder();
        while (matcher.find()) {
            URI uri = URI.create(matcher.group(1).trim());
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null)
                throw new IllegalArgumentException("Invalid file URL");
            files.add(uri.toString());
            matcher.appendReplacement(markdown, "");
        }
        matcher.appendTail(markdown);
        if (files.size() > 3) throw new IllegalArgumentException("Too many files in one message");

        if (!markdown.toString().isBlank()
                && !context.send(true, PendingMessage.ofMarkdownRaw(markdown.toString())).success())
            throw new IllegalStateException("Agent message delivery failed");
        for (String url : files) {
            if (!context.send(true, PendingMessage.ofFileUrl(url)).success())
                throw new IllegalStateException("Agent file delivery failed");
        }
    }

    static List<Attachment> collectAttachments(List<Attachment> attachments, List<MsgElem> elements) {
        var candidates = new java.util.ArrayList<Attachment>();
        if (attachments != null) candidates.addAll(attachments);
        if (elements != null) {
            for (MsgElem element : elements) {
                if (element != null && element.attachments() != null) candidates.addAll(element.attachments());
            }
        }

        var captured = new java.util.ArrayList<Attachment>();
        var seen = new java.util.HashSet<String>();
        for (Attachment attachment : candidates) {
            if (attachment == null || !seen.add(attachment.url())) continue;
            if (attachment.contentType() == null || !attachment.contentType().startsWith("image/")) continue;
            try {
                captured.add(ImageCache.capture(attachment));
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                captured.add(new Attachment("image/png", attachment.filename(), 0, "unavailable:"));
            }
        }
        return captured;

//        var urls = new LinkedHashSet<String>();
//        for (Attachment attachment : candidates) {
//            if (attachment == null || attachment.url() == null || attachment.url().isBlank()) continue;
//
//            String url = attachment.url().trim();
//            if (url.startsWith("//")) url = "https:" + url;
//
//            try {
//                URI uri = URI.create(url);
//                if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
//                        || uri.getHost() == null || uri.getUserInfo() != null) continue;
//
//                String type = attachment.contentType() == null ? ""
//                        : attachment.contentType().split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
//                boolean image = type.startsWith("image/");
//                if (type.isEmpty() || type.equals("application/octet-stream")) {
//                    // Some QQ attachments omit their MIME type; use the filename or URL path in that case.
//                    image = isImageFilename(attachment.filename()) || isImageFilename(uri.getPath());
//                }
//                if (image) urls.add(uri.toASCIIString());
//            } catch (IllegalArgumentException ignored) {
//                // Ignore malformed attachment URLs without discarding the rest of the user's message.
//            }
//        }

//        return List.copyOf(urls);
    }

    private static boolean isImageFilename(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).matches(".*\\.(png|jpe?g|webp|gif)$");
    }

    public synchronized boolean enabled(String groupId) {
        return engine != null && AiPermission.isActivated(groupId);
    }

    public void reload() throws IOException {
        String nextPrompt = Files.readString(Path.of(settings.promptFile()));
        if (nextPrompt.isBlank()) throw new IllegalArgumentException("Agent prompt is empty");

        HttpTools.reload(Path.of(settings.toolsFile()), tools);
        prompt = nextPrompt;

        AiPermission.loadFromFile();
    }

    public void handleChat(Context ctx, String text, List<Attachment> attachments, List<MsgElem> elements) {
        if (!ctx.inGroup() || !enabled(ctx.groupId())) return;

        try {
            engine.accept(
                    ctx.groupId(),
                    new AgentEngine.Message(
                            ctx.messageId(),
                            ctx.senderUserId(),
                            ctx.senderNickname(),
                            text,
                            collectAttachments(attachments, elements),
                            System.currentTimeMillis(),
                            message -> {
                                if (!enabled(ctx.groupId())) return;
                                sendResponse(ctx, message);
                            })
            );
        } catch (java.util.concurrent.RejectedExecutionException e) {
            ctx.sendReply(at(ctx) + "本群待处理消息过多，请稍后重试喵。");
        }
    }

    public void recordHistory(String groupId, String messageId, String senderId, String nickname,
                              String text, List<Attachment> attachments, List<MsgElem> elements) {
        if (groupId == null || senderId == null || !enabled(groupId)) return;

//        var att = collectAttachments(attachments, elements);
        if (text == null || text.isBlank()) return;

        engine.accept(groupId, new AgentEngine.Message(messageId, senderId, nickname,
                text, List.of(), System.currentTimeMillis(), null));
    }

    public void rememberNames(String groupId, java.util.Map<String, String> names) {
        if (enabled(groupId)) engine.rememberNames(groupId, names);
    }

    public void handleAi(Context context) {
        if (!context.inGroup()) {
            context.sendReply(at(context) + "请在群聊中使用此命令喵。");
            return;
        }

        String action = context.argumentCount() == 0 ? "status" : context.argument(0);
        if (action.equals("status")) {
            context.sendReply(at(context) + (engine == null ? "AI 未配置或未启用喵。" : enabled(context.groupId()) ? "本群 Agent 已开启喵。" : "本群 Agent 已关闭喵。"));
            return;
        }

        if (!context.hasAdmin() && !administrator.test(context.senderUserId())) {
            context.sendReply(at(context) + "此操作需要机器人管理员权限喵。");
            return;
        }

        if (engine == null) {
            context.sendReply(at(context) + "AI 未配置或未启用喵。");
            return;
        }

        try {
            switch (action) {
                case "confirm", "deny" -> {
                    boolean accepted = (
                            context.argumentCount() == 2 && tools.decide(
                                    context.argument(1),
                                    context.groupId(),
                                    context.senderUserId(),
                                    action.equals("confirm")
                            )
                    );
                    context.sendReply(at(context) + (accepted ? "决定已提交喵。" : "确认请求不存在、已处理或已过期喵。"));
                }
                case "grant", "revoke" -> {
                    if (!administrator.test(context.senderUserId())) {
                        context.sendReply(at(context) + "此操作需要机器人管理员权限喵。");
                        return;
                    }
                    if (action.equals("grant")) AiPermission.grant(context.groupId());
                    else AiPermission.revoke(context.groupId());
                    context.sendReply(at(context) + "AI 授权已更新喵。");
                }
                case "on", "off" -> {
                    if (action.equals("on")) AiPermission.activate(context.groupId());
                    else AiPermission.deactivate(context.groupId());

                    context.sendReply(at(context) + (action.equals("on") ? "本群 AI 聊天已开启喵。" : "本群 AI 聊天已关闭。"));
                }
                case "reset" ->
                        context.sendReply(at(context) + (engine.reset(context.groupId()) ? "已清除本群上下文喵" : "请等待本群当前任务结束后再清除喵。"));
                case "reload" -> {
                    if (!administrator.test(context.senderUserId())) {
                        context.sendReply(at(context) + "此操作需要机器人管理员权限喵。");
                    } else {
                        reload();
                        context.sendReply(at(context) + "AI 聊天已重新加载喵。");
                    }
                }
                default ->
                        context.sendReply(at(context) + "用法：/ai status|on|off|grant|revoke|reset|reload，或 /ai confirm|deny TOKEN");
            }
        } catch (Exception e) {
            org.apache.logging.log4j.LogManager.getLogger(AgentChatHandler.class).error("Agent management failed", e);
            context.sendReply(at(context) + "指令处理失败了喵。\n> " + e.getMessage());
        }
    }

    public Set<String> activeGroupIds() {
        return engine == null ? Set.of() : engine.activeConversationIds();
    }

    @Override
    public void close() {
        if (engine != null) engine.close();
        else tools.close();
    }
}
