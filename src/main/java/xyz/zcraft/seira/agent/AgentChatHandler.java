package xyz.zcraft.seira.agent;

import xyz.zcraft.seira.bot.data.Attachment;
import xyz.zcraft.seira.bot.data.FileInfo;
import xyz.zcraft.seira.bot.data.GroupBotState;
import xyz.zcraft.seira.bot.data.MsgElem;
import xyz.zcraft.seira.bot.data.msg.Button;
import xyz.zcraft.seira.bot.data.msg.MDMessage;
import xyz.zcraft.seira.bot.data.msg.PendingMessage;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.config.AgentConfig;
import xyz.zcraft.seira.config.AsteroidConfig;
import xyz.zcraft.seira.config.OstellaConfig;
import xyz.zcraft.seira.services.AiPermission;
import xyz.zcraft.seira.util.ResourceUtil;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

import static xyz.zcraft.seira.command.reply.ReplyFactory.ExternalUrls.CHANNEL;
import static xyz.zcraft.seira.command.reply.ReplyFactory.ExternalUrls.PERMISSION;
import static xyz.zcraft.seira.command.reply.ReplyFactory.at;

public final class AgentChatHandler implements AutoCloseable {
    public static AtomicReference<AgentChatHandler> INSTANCE = new AtomicReference<>();
    private final Predicate<String> administrator;
    private final AgentConfig settings;
    private final ToolRegistry tools;
    private final AgentEngine engine;
    private final Function<String, GroupBotState> botStateGetter;
    private final BiFunction<String, String, FileInfo> privateFileUploader;
    private final BiFunction<String, String, FileInfo> groupFileUploader;
    private final Set<String> consented = new HashSet<>();
    private final ExecutorService fileUploadExecutor = Executors.newSingleThreadExecutor();
    private volatile String prompt;

    public AgentChatHandler(
            Predicate<String> administrator, AgentConfig conf, Function<String, String> osuToken,
            OstellaConfig ostella, AsteroidConfig asteroid, Function<String, GroupBotState> botStateGetter,
            BiFunction<String, String, FileInfo> privateFileUploader, BiFunction<String, String, FileInfo> groupFileUploader) {
        this.administrator = administrator;
        tools = new ToolRegistry(administrator, this::enabled);
        this.botStateGetter = botStateGetter;
        this.privateFileUploader = privateFileUploader;
        this.groupFileUploader = groupFileUploader;

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

    public static List<Attachment> collectAttachments(List<Attachment> attachments, List<MsgElem> elements) {
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

    public static String getRefContent(List<MsgElem> elements) {
        return elements.stream()
                .filter(e -> e.messageType() == 103)
                .filter(e -> e.content() != null && !e.content().isBlank())
                .findFirst()
                .map(MsgElem::content)
                .orElse(null);
    }

    private static boolean isImageFilename(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).matches(".*\\.(png|jpe?g|webp|gif)$");
    }

    private void sendResponse(Context context, PendingMessage message) {
        String messageText;
        List<List<Button>> messageButtons;
        if (message instanceof MDMessage md) {
            messageText = md.getMarkdown();
            messageButtons = md.getButtons();
        } else {
            messageText = message.getContent();
            messageButtons = List.of();
        }
        var matcher = java.util.regex.Pattern.compile("%%\\[file:([^\\r\\n]+?)]%%").matcher(messageText);
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
        if (files.size() > 8) throw new IllegalArgumentException("Too many files in one message");

        String append = "";
        if (!files.isEmpty()) {
            append = "\n\n> " + files.size() + " 个附件上传中...";
        }

        final PendingMessage finalMessage = PendingMessage.ofMarkdownRaw((markdown + append).trim(), messageButtons);
        if (!markdown.toString().isBlank() && !context.send(true, finalMessage).success()) {
            throw new IllegalStateException("Agent message delivery failed");
        }

        for (String url : files) {
            fileUploadExecutor.submit(() -> {
                var fileInfo = context.inGroup()
                        ? groupFileUploader.apply(context.groupId(), url)
                        : privateFileUploader.apply(context.senderUserId(), url);
                if (!context.send(true, PendingMessage.ofUploadedFile(fileInfo, url)).success()) {
                    throw new IllegalStateException("Agent file delivery failed");
                }
            });
        }
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
                            getRefContent(elements),
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
                text, getRefContent(elements), List.of(), System.currentTimeMillis(), null));
    }

    public void rememberNames(String groupId, java.util.Map<String, String> names) {
        if (enabled(groupId)) engine.rememberNames(groupId, names);
    }

    public void handleAi(Context ctx) {
        if (!ctx.inGroup()) {
            ctx.sendReply(at(ctx) + "请在群聊中使用此命令喵。");
            return;
        }

        String action = ctx.argumentCount() == 0 ? "status" : ctx.argument(0);

        if (action.equals("status")) {
            final boolean b = AiPermission.permits(ctx.groupId());
            final boolean c = AiPermission.isActivated(ctx.groupId());
            ctx.sendReply(at(ctx) + "目前AI对话在本群状态\n" +
                    "> 已授权：" + (b ? "√" : "×") + "\n" +
                    "> 已启用：" + (c ? "√" : "×")
            );
            return;
        }

        if (!ctx.hasAdmin() && !administrator.test(ctx.senderUserId())) {
            ctx.sendReply(at(ctx) + "此操作需要机器人管理员权限喵。");
            return;
        }

        if (engine == null) {
            ctx.sendReply(at(ctx) + "AI 未配置或未启用喵。");
            return;
        }

        try {
            switch (action) {
                case "confirm", "deny" -> {
                    boolean accepted = (
                            ctx.argumentCount() == 2 && tools.decide(
                                    ctx.argument(1),
                                    ctx.groupId(),
                                    ctx.senderUserId(),
                                    action.equals("confirm")
                            )
                    );
                    ctx.sendReply(at(ctx) + (accepted ? "决定已提交喵。" : "确认请求不存在、已处理或已过期喵。"));
                }
                case "grant", "revoke" -> {
                    if (!administrator.test(ctx.senderUserId())) {
                        ctx.sendReply(at(ctx) + "此操作需要机器人管理员权限喵。");
                        return;
                    }
                    if (action.equals("grant")) AiPermission.grant(ctx.groupId());
                    else AiPermission.revoke(ctx.groupId());
                    ctx.sendReply(at(ctx) + "AI 授权已更新喵。");
                }
                case "on", "off" -> {
                    if (action.equals("on")) {
                        final GroupBotState state = botStateGetter.apply(ctx.groupId());
                        if (state.allowProactiveMsg() && state.receiveMsgSetting() == GroupBotState.ReceiveMsgSetting.ALL) {
                            if (AiPermission.isActivated(ctx.groupId())) {
                                ctx.sendReply(at(ctx) + "本群AI已为启用状态喵。");
                            } else if (AiPermission.permits(ctx.groupId()) && !consented.contains(ctx.groupId())) {
                                final String terms = ResourceUtil.loadString("/ai-chat-terms.md").orElse("文件加载失败了喵");
                                ctx.sendReply("> 使用 AI 对话功能即表示群内成员同意并遵守以下条款：\n\n" + terms + "\n\n> 本消息仅展示一次，再次使用 `/ai on` 即可开启 AI 对话。");
                                consented.add(ctx.groupId());
                            } else if (AiPermission.permits(ctx.groupId())) {
                                AiPermission.activate(ctx.groupId());
                                ctx.sendReply(at(ctx) + "已启用本群AI对话喵。");
                            } else {
                                if (administrator.test(ctx.senderUserId())) {
                                    AiPermission.permits(ctx.groupId());
                                    AiPermission.activate(ctx.groupId());
                                    ctx.sendReply(at(ctx) + "已授权并启用本群AI对话喵。");
                                } else {
                                    ctx.sendReply(at(ctx) + "本群无此功能权限，请 [联系 Bot 管理员](%s) 喵。".formatted(CHANNEL));
                                }
                            }
                        } else {
                            ctx.sendReply(PendingMessage.ofMarkdownRaw(
                                    at(ctx) + "由于本群群主未配置权限或配置不完整，暂无法启用本群AI对话喵。" +
                                            "权限配置**群主**见[这里](" + PERMISSION + ")~")
                            );
                        }
                    } else {
                        if (AiPermission.isActivated(ctx.groupId())) {
                            AiPermission.deactivate(ctx.groupId());
                            ctx.sendReply(at(ctx) + "已禁用本群AI对话喵。");
                        } else {
                            ctx.sendReply(at(ctx) + "本群AI对话未启用喵。");
                        }
                    }
                }
                case "reset" ->
                        ctx.sendReply(at(ctx) + (engine.reset(ctx.groupId()) ? "已清除本群上下文喵" : "请等待本群当前任务结束后再清除喵。"));
                case "reload" -> {
                    if (!administrator.test(ctx.senderUserId())) {
                        ctx.sendReply(at(ctx) + "此操作需要机器人管理员权限喵。");
                    } else {
                        reload();
                        ctx.sendReply(at(ctx) + "AI 聊天已重新加载喵。");
                    }
                }
                case "legacy" -> {
                    if (!administrator.test(ctx.senderUserId())) {
                        ctx.sendReply(at(ctx) + "此操作需要机器人管理员权限喵。");
                        return;
                    }
                    AiPermission.setUseLegacy(!AiPermission.doUseLegacy());
                    ctx.sendReply(at(ctx) + "已切换到" + (AiPermission.doUseLegacy() ? "旧版" : "新版") + "AI聊天模式喵。");
                }
                default ->
                        ctx.sendReply(at(ctx) + "用法：/ai status|on|off|grant|revoke|reset|reload，或 /ai confirm|deny TOKEN");
            }
        } catch (Exception e) {
            org.apache.logging.log4j.LogManager.getLogger(AgentChatHandler.class).error("Agent management failed", e);
            ctx.sendReply(at(ctx) + "指令处理失败了喵。\n> " + e.getMessage());
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
