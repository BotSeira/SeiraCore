package xyz.zcraft.seira.agent;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import xyz.zcraft.seira.bot.data.Attachment;
import xyz.zcraft.seira.bot.data.MsgElem;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.config.AgentConfig;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.function.Predicate;

import static xyz.zcraft.seira.command.reply.ReplyFactory.at;

public final class AgentChatHandler implements AutoCloseable {
    private static final Path GROUPS = Path.of("data", "agent-groups.json");

    private final Predicate<String> administrator;
    private final Set<String> enabledGroups = new HashSet<>();
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
            if (Files.exists(GROUPS)) {
                JsonParser.parseString(Files.readString(GROUPS)).getAsJsonArray()
                        .forEach(value -> enabledGroups.add(value.getAsString()));
            }

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
            } else engine = null;
        } catch (IOException e) {
            tools.close();
            throw new IllegalStateException("Cannot load agent configuration", e);
        }
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

    static List<String> collectImageUrls(List<Attachment> attachments, List<MsgElem> elements) {
        var candidates = new java.util.ArrayList<Attachment>();
        if (attachments != null) candidates.addAll(attachments);
        if (elements != null) {
            for (MsgElem element : elements) {
                if (element != null && element.attachments() != null) candidates.addAll(element.attachments());
            }
        }

        var urls = new LinkedHashSet<String>();
        for (Attachment attachment : candidates) {
            if (attachment == null || attachment.url() == null || attachment.url().isBlank()) continue;

            String url = attachment.url().trim();
            if (url.startsWith("//")) url = "https:" + url;

            try {
                URI uri = URI.create(url);
                if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                        || uri.getHost() == null || uri.getUserInfo() != null) continue;

                String type = attachment.contentType() == null ? ""
                        : attachment.contentType().split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
                boolean image = type.startsWith("image/");
                if (type.isEmpty() || type.equals("application/octet-stream")) {
                    // Some QQ attachments omit their MIME type; use the filename or URL path in that case.
                    image = isImageFilename(attachment.filename()) || isImageFilename(uri.getPath());
                }
                if (image) urls.add(uri.toASCIIString());
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed attachment URLs without discarding the rest of the user's message.
            }
        }

        return List.copyOf(urls);
    }

    private static boolean isImageFilename(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).matches(".*\\.(png|jpe?g|webp|gif)$");
    }

    public synchronized boolean enabled(String groupId) {
        return engine != null && enabledGroups.contains(groupId);
    }

    private void reload() throws IOException {
        String nextPrompt = Files.readString(Path.of(settings.promptFile()));
        if (nextPrompt.isBlank()) throw new IllegalArgumentException("Agent prompt is empty");

        HttpTools.reload(Path.of(settings.toolsFile()), tools);
        prompt = nextPrompt;
    }

    public void handleChat(Context ctx, String text, List<Attachment> attachments, List<MsgElem> elements) {
        if (!ctx.inGroup() || !enabled(ctx.groupId())) return;

        try {
            engine.accept(ctx.groupId(), new AgentEngine.Message(ctx.messageId(), ctx.senderUserId(),
                    ctx.senderNickname(), text, collectImageUrls(attachments, elements), System.currentTimeMillis(), message -> {
                if (!enabled(ctx.groupId())) return;
                sendResponse(ctx, message);
            }));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            ctx.sendReply(at(ctx) + "本群待处理消息过多，请稍后重试喵。");
        }
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
                case "on", "off" -> {
                    synchronized (this) {
                        Set<String> next = new HashSet<>(enabledGroups);
                        if (action.equals("on")) next.add(context.groupId());
                        else next.remove(context.groupId());

                        Files.createDirectories(GROUPS.getParent());
                        Path temporary = GROUPS.resolveSibling("agent-groups.json.tmp");
                        Files.writeString(temporary, new Gson().toJson(next));
                        Files.move(temporary, GROUPS, StandardCopyOption.REPLACE_EXISTING);

                        enabledGroups.clear();
                        enabledGroups.addAll(next);
                    }

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
                        context.sendReply(at(context) + "用法：/ai status|on|off|reset|reload，或 /ai confirm|deny TOKEN");
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
