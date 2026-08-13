package xyz.zcraft.seira.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.EmbedType;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.seira.binding.UserDataStore;
import xyz.zcraft.seira.bot.MessageSender;
import xyz.zcraft.seira.bot.data.Attachment;
import xyz.zcraft.seira.bot.data.FileInfo;
import xyz.zcraft.seira.bot.data.MDMessage;
import xyz.zcraft.seira.bot.data.Message;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.config.BridgeConfig;
import xyz.zcraft.seira.config.DiscordConfig;
import xyz.zcraft.seira.config.DiscordProxyConfig;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class DiscordBridgeService implements AutoCloseable {
    private static final Logger LOG = LogManager.getLogger(DiscordBridgeService.class);

    private final DiscordConfig discordConfig;
    private final BridgeConfig bridgeConfig;
    private final MessageSender qqSender;
    private final MediaDownloader discordMediaDownloader;
    private final MediaDownloader qqMediaDownloader;
    private final ExecutorService workers;
    private final Map<String, SerialExecutor> queues = new ConcurrentHashMap<>();
    private final Map<String, DiscordBridgeMapping> mappings = new ConcurrentHashMap<>();
    private final AtomicReference<JDA> jda = new AtomicReference<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile String connectionError;

    public DiscordBridgeService(DiscordConfig discordConfig, BridgeConfig bridgeConfig, MessageSender qqSender) {
        this.discordConfig = java.util.Objects.requireNonNull(discordConfig);
        this.bridgeConfig = java.util.Objects.requireNonNull(bridgeConfig);
        this.qqSender = java.util.Objects.requireNonNull(qqSender);
        this.discordMediaDownloader = new MediaDownloader(bridgeConfig.maxMediaBytes(), discordConfig.proxy());
        this.qqMediaDownloader = new MediaDownloader(bridgeConfig.maxMediaBytes(), DiscordProxyConfig.disabled());
        this.workers = Executors.newFixedThreadPool(
                bridgeConfig.workerThreads(),
                Thread.ofPlatform().daemon().name("seira-discord-bridge-", 0).factory()
        );
        UserDataStore.findAllDiscordBridges().forEach(mapping -> mappings.put(mapping.groupId(), mapping));
    }

    public void start() {
        if (closed.get()) throw new IllegalStateException("Discord bridge has already been closed");
        if (!started.compareAndSet(false, true)) return;
        if (!discordConfig.enabled()) {
            LOG.info("Discord bridge is disabled because discord.token is blank");
            return;
        }
        try {
            JDABuilder builder = JDABuilder.createDefault(discordConfig.token())
                    .enableIntents(GatewayIntent.MESSAGE_CONTENT)
                    .addEventListeners(new DiscordListener());
            DiscordProxyConfigurator.apply(builder, discordConfig.proxy());
            JDA created = builder.build();
            jda.set(created);
            LOG.info("Discord bridge connection started; {} persisted mapping(s) loaded", mappings.size());
        } catch (RuntimeException e) {
            connectionError = e.getMessage();
            LOG.error("Failed to start Discord bridge", e);
        }
    }

    public BindResult bind(String groupId, DcsTarget target) {
        if (!discordConfig.enabled()) {
            return BindResult.failed("Discord 同步尚未启用，请先配置 discord.token 并重启机器人。");
        }
        JDA current = jda.get();
        if (current == null || current.getStatus() != JDA.Status.CONNECTED) {
            String detail = connectionError == null || connectionError.isBlank() ? "Discord 尚未连接就绪" : connectionError;
            return BindResult.failed(detail + "，请稍后重试。");
        }
        TextChannel channel = current.getTextChannelById(target.channelId());
        if (channel == null || !channel.getGuild().getId().equals(target.guildId())) {
            return BindResult.failed("找不到指定服务器中的文字频道，请确认 ID 以及机器人是否已加入并可见该频道。");
        }
        if (!channel.canTalk()) {
            return BindResult.failed("Discord 机器人没有在该频道发送消息的权限。");
        }
        DiscordBridgeMapping mapping = new DiscordBridgeMapping(groupId, target.guildId(), target.channelId());
        UserDataStore.upsertDiscordBridge(mapping);
        mappings.put(groupId, mapping);
        return BindResult.success(channel.getGuild().getName(), channel.getName());
    }

    public boolean unbind(String groupId) {
        boolean removed = UserDataStore.removeDiscordBridge(groupId);
        mappings.remove(groupId);
        return removed;
    }

    public DiscordBridgeMapping mappingForGroup(String groupId) {
        return mappings.get(groupId);
    }

    public void acceptQqMessage(QqIncomingMessage message) {
        DiscordBridgeMapping mapping = mappings.get(message.groupId());
        if (mapping == null || isDcsCommand(message.text())) return;
        queue("qq:" + mapping.guildId() + ':' + mapping.channelId())
                .execute(() -> relayQqToDiscord(mapping, message));
    }

    /** Relays the portable part of a QQ command result to the mapped Discord channel. */
    public void acceptQqCommandReply(String groupId, PendingMessage message) {
        DiscordBridgeMapping mapping = mappings.get(groupId);
        if (mapping == null || message == null) return;
        queue("qq:" + mapping.guildId() + ':' + mapping.channelId())
                .execute(() -> relayQqCommandReply(mapping, groupId, message));
    }

    private void relayQqCommandReply(
            DiscordBridgeMapping mapping,
            String groupId,
            PendingMessage message
    ) {
        try {
            if (!mapping.equals(mappings.get(groupId))) return;
            String content = message instanceof MDMessage markdown
                    ? markdown.getMarkdown()
                    : message.getContent();
            StringBuilder body = new StringBuilder(BridgeFormatter.normalizeQqText(content, Map.of()));
            List<DownloadedMedia> media = new ArrayList<>();

            for (String imageUrl : BridgeFormatter.findImageUrls(body.toString())) {
                try {
                    media.add(qqMediaDownloader.download(imageUrl, "command-result"));
                    replaceBody(body, BridgeFormatter.removeSourceUrl(body.toString(), imageUrl));
                } catch (Exception e) {
                    LOG.warn("Could not download command reply image {}: {}", imageUrl, e.getMessage());
                }
            }

            if (message.getFileBase64() != null) {
                try {
                    byte[] data = Base64.getDecoder().decode(message.getFileBase64());
                    if (data.length > bridgeConfig.maxMediaBytes()) {
                        throw new IllegalArgumentException("Media exceeds configured size limit");
                    }
                    String contentType = commandMediaContentType(message.getFileType(), data);
                    media.add(new DownloadedMedia(
                            data,
                            "command-result" + MediaFormat.extensionFor(contentType),
                            contentType,
                            ""
                    ));
                } catch (IllegalArgumentException e) {
                    appendLine(body, "[媒体无法转发到 Discord]");
                    LOG.warn("Could not decode command reply media: {}", e.getMessage());
                }
            } else if (message.getFileUrl() != null && !message.getFileUrl().isBlank()) {
                try {
                    media.add(qqMediaDownloader.download(message.getFileUrl(), "command-result"));
                } catch (Exception e) {
                    appendLine(body, "[媒体无法转发到 Discord] " + message.getFileUrl());
                    LOG.warn("Could not download command reply media {}: {}", message.getFileUrl(), e.getMessage());
                }
            }

            String rendered = BridgeFormatter.escapeDiscordMentions(body.toString().strip());
            if (!rendered.isBlank() || !media.isEmpty()) {
                sendDiscordBatches(mapping, rendered, media, "Seira", "Seira");
            }
        } catch (Exception e) {
            LOG.error("Failed to relay QQ command reply for group {} to Discord", groupId, e);
        }
    }

    private void relayQqToDiscord(DiscordBridgeMapping mapping, QqIncomingMessage message) {
        try {
            if (!mapping.equals(mappings.get(message.groupId()))) return;
            StringBuilder body = new StringBuilder(BridgeFormatter.normalizeQqText(message));
            List<DownloadedMedia> media = new ArrayList<>();
            for (Attachment attachment : message.attachments()) {
                try {
                    media.add(qqMediaDownloader.download(attachment.url(), attachment.filename()));
                } catch (Exception e) {
                    appendLine(body, "[媒体无法转发: " + displayFilename(attachment.filename()) + "] " + attachment.url());
                    LOG.warn("Could not download QQ attachment {}: {}", attachment.url(), e.getMessage());
                }
            }
            String name = message.senderName() == null || message.senderName().isBlank()
                    ? message.senderId()
                    : message.senderName();
            String rendered = BridgeFormatter.escapeDiscordMentions(BridgeFormatter.render(
                    bridgeConfig.qqToDiscordFormat(), name, message.senderId(), body.toString()
            ));
            sendDiscordBatches(mapping, rendered, media, name, message.senderId());
        } catch (Exception e) {
            LOG.error("Failed to relay QQ message {} to Discord", message.messageId(), e);
        }
    }

    private void relayDiscordToQq(String groupId, DiscordIncomingMessage message) {
        try {
            DiscordBridgeMapping currentMapping = mappings.get(groupId);
            if (currentMapping == null
                    || !currentMapping.guildId().equals(message.guildId())
                    || !currentMapping.channelId().equals(message.channelId())) {
                return;
            }
            String body = message.text();
            StringBuilder notes = new StringBuilder();
            List<PreparedQqMedia> media = new ArrayList<>();
            for (BridgeAttachment attachment : message.attachments()) {
                try {
                    PreparedQqMedia prepared = prepareQqMedia(groupId, attachment);
                    if (prepared == null) throw new IllegalStateException("No media candidate could be uploaded to QQ");
                    media.add(prepared);
                    body = BridgeFormatter.removeSourceUrl(body, attachment.sourceTextUrl());
                    if (!prepared.media().contentType().startsWith("image/") && !attachment.animatedExpression()) {
                        appendLine(notes, "[文件: " + prepared.media().filename() + "]");
                    }
                } catch (Exception e) {
                    String explanation = attachment.animatedExpression()
                            ? "[GIF 表情转发失败：媒体源不可用]"
                            : "[媒体无法转发: " + displayFilename(attachment.filename()) + "] "
                            + firstUrl(attachment.candidateUrls());
                    appendLine(notes, explanation);
                    LOG.warn("Could not download Discord attachment {}: {}", attachment.filename(), e.getMessage());
                }
            }

            String rendered = BridgeFormatter.render(
                    bridgeConfig.discordToQqFormat(), message.authorName(), message.authorId(), body + notes
            );
            for (String part : BridgeFormatter.splitQqText(rendered)) {
                qqSender.sendGroupText(groupId, part);
            }
            for (PreparedQqMedia item : media) {
                sendPreparedQqMedia(groupId, item);
            }
        } catch (Exception e) {
            LOG.error("Failed to relay Discord message from {} to QQ group {}", message.authorName(), groupId, e);
        }
    }

    private PreparedQqMedia prepareQqMedia(String groupId, BridgeAttachment attachment) {
        for (String candidate : attachment.candidateUrls()) {
            try {
                DownloadedMedia item = discordMediaDownloader.download(candidate, attachment.filename());
                if (attachment.animatedExpression()
                        && !item.contentType().startsWith("image/")
                        && !item.contentType().startsWith("video/")) {
                    continue;
                }
                FileInfo uploaded = qqSender.uploadGroupMediaBase64(
                        groupId,
                        qqFileType(item.contentType()),
                        Base64.getEncoder().encodeToString(item.data())
                );
                if (uploaded != null) return new PreparedQqMedia(item, uploaded);
            } catch (Exception e) {
                LOG.debug("Discord media candidate {} was not usable: {}", candidate, e.getMessage());
            }
        }
        return null;
    }

    private void sendPreparedQqMedia(String groupId, PreparedQqMedia item) {
        Message outbound = new Message();
        outbound.setMsgType(PendingMessage.MSG_TYPE_MEDIA);
        outbound.setMedia(item.uploaded());
        if (!qqSender.sendGroupMessage(groupId, outbound)) {
            qqSender.sendGroupText(groupId, "[媒体发送到 QQ 失败: " + item.media().filename() + "]");
        }
    }

    private void sendDiscordBatches(
            DiscordBridgeMapping mapping,
            String content,
            List<DownloadedMedia> media,
            String senderName,
            String senderId
    ) {
        JDA current = jda.get();
        TextChannel destination = current == null ? null : current.getTextChannelById(mapping.channelId());
        if (destination == null || !destination.getGuild().getId().equals(mapping.guildId())) {
            throw new IllegalStateException("Discord bridge destination is unavailable");
        }
        List<String> textChunks = BridgeFormatter.splitDiscordText(content);
        List<List<DownloadedMedia>> mediaBatches = batchMedia(media);
        int count = Math.max(textChunks.size(), mediaBatches.size());
        for (int index = 0; index < count; index++) {
            String part = index < textChunks.size() ? textChunks.get(index) : "";
            if (part.isBlank() && index > 0 && index < mediaBatches.size()) {
                part = BridgeFormatter.escapeDiscordMentions(BridgeFormatter.render(
                        bridgeConfig.qqToDiscordFormat(), senderName, senderId, "[媒体续传]"
                ));
            }
            List<DownloadedMedia> files = index < mediaBatches.size() ? mediaBatches.get(index) : List.of();
            MessageCreateBuilder outbound = new MessageCreateBuilder();
            if (!part.isBlank()) outbound.setContent(part);
            List<FileUpload> uploads = files.stream()
                    .map(item -> FileUpload.fromData(item.data(), item.filename()))
                    .toList();
            destination.sendMessage(outbound.build()).addFiles(uploads).complete();
        }
    }

    private List<List<DownloadedMedia>> batchMedia(List<DownloadedMedia> media) {
        List<List<DownloadedMedia>> batches = new ArrayList<>();
        List<DownloadedMedia> current = new ArrayList<>();
        long currentBytes = 0;
        for (DownloadedMedia item : media) {
            if (!current.isEmpty() && (current.size() >= bridgeConfig.maxDiscordAttachments()
                    || currentBytes + item.data().length > bridgeConfig.maxDiscordBatchBytes())) {
                batches.add(List.copyOf(current));
                current.clear();
                currentBytes = 0;
            }
            current.add(item);
            currentBytes += item.data().length;
        }
        if (!current.isEmpty()) batches.add(List.copyOf(current));
        return List.copyOf(batches);
    }

    private SerialExecutor queue(String key) {
        return queues.computeIfAbsent(key, ignored -> new SerialExecutor(workers));
    }

    private List<String> groupsFor(String guildId, String channelId) {
        return mappings.values().stream()
                .filter(mapping -> mapping.guildId().equals(guildId) && mapping.channelId().equals(channelId))
                .map(DiscordBridgeMapping::groupId)
                .toList();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        JDA current = jda.getAndSet(null);
        if (current != null) current.shutdown();
        workers.shutdownNow();
    }

    private final class DiscordListener extends ListenerAdapter {
        @Override
        public void onReady(ReadyEvent event) {
            connectionError = null;
            LOG.info("Discord bridge connected as {}", event.getJDA().getSelfUser().getName());
        }

        @Override
        public void onMessageReceived(MessageReceivedEvent event) {
            if (!event.isFromGuild() || event.getAuthor().isBot() || event.isWebhookMessage()) return;
            String guildId = event.getGuild().getId();
            String channelId = event.getChannel().getId();
            List<String> groups = groupsFor(guildId, channelId);
            if (groups.isEmpty()) return;

            String authorName = event.getMember() == null
                    ? event.getAuthor().getEffectiveName()
                    : event.getMember().getEffectiveName();
            DiscordIncomingMessage incoming = new DiscordIncomingMessage(
                    guildId,
                    channelId,
                    event.getAuthor().getId(),
                    authorName,
                    event.getMessage().getContentDisplay(),
                    collectDiscordAttachments(event)
            );
            for (String groupId : groups) {
                queue("discord:" + groupId).execute(() -> relayDiscordToQq(groupId, incoming));
            }
        }
    }

    private static List<BridgeAttachment> collectDiscordAttachments(MessageReceivedEvent event) {
        List<BridgeAttachment> attachments = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String url : BridgeFormatter.findImageUrls(event.getMessage().getContentDisplay())) {
            if (seen.add(url)) {
                attachments.add(new BridgeAttachment(
                        filenameFromUrl(url), List.of(url), url, isGifUrl(url)
                ));
            }
        }
        event.getMessage().getAttachments().forEach(item -> {
            if (seen.add(item.getUrl())) {
                attachments.add(new BridgeAttachment(
                        item.getFileName(), distinct(item.getUrl(), item.getProxyUrl()), null,
                        "image/gif".equalsIgnoreCase(item.getContentType())
                ));
            }
        });
        for (MessageEmbed embed : event.getMessage().getEmbeds()) {
            if (embed.getType() == EmbedType.GIFV && embed.getVideoInfo() != null) {
                List<String> candidates = gifCandidates(embed);
                String key = candidates.isEmpty() ? null : candidates.getFirst();
                if (key != null && seen.add(key)) {
                    attachments.add(new BridgeAttachment("discord-gif.gif", candidates, embed.getUrl(), true));
                }
                continue;
            }
            MessageEmbed.ImageInfo image = embed.getImage();
            if (image != null && seen.add(image.getUrl())) {
                attachments.add(new BridgeAttachment(
                        "embed-image", distinct(image.getUrl(), image.getProxyUrl()), embed.getUrl(), false
                ));
            }
        }
        event.getMessage().getStickers().forEach(sticker -> {
            if (seen.add(sticker.getIconUrl())) {
                attachments.add(new BridgeAttachment(
                        sticker.getName(), List.of(sticker.getIconUrl()), null,
                        sticker.getIconUrl().toLowerCase(Locale.ROOT).contains(".gif")
                ));
            }
        });
        event.getMessage().getMentions().getCustomEmojis().forEach(emoji -> {
            if (seen.add(emoji.getImageUrl())) {
                attachments.add(new BridgeAttachment(
                        "emoji-" + emoji.getName(), List.of(emoji.getImageUrl()), null, emoji.isAnimated()
                ));
            }
        });
        return List.copyOf(attachments);
    }

    private static String filenameFromUrl(String value) {
        try {
            String path = URI.create(value).getPath();
            if (path != null && path.lastIndexOf('/') < path.length() - 1) {
                return path.substring(path.lastIndexOf('/') + 1);
            }
        } catch (IllegalArgumentException ignored) {
            // The downloader will report malformed URLs if one reaches it.
        }
        return "discord-image";
    }

    private static boolean isGifUrl(String value) {
        try {
            String path = URI.create(value).getPath();
            return path != null && path.toLowerCase(Locale.ROOT).endsWith(".gif");
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    static List<String> gifCandidates(MessageEmbed embed) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        MessageEmbed.VideoInfo video = embed.getVideoInfo();
        if (video != null) {
            addDerivedGif(candidates, video.getUrl());
            addDerivedGif(candidates, video.getProxyUrl());
            add(candidates, video.getUrl());
            add(candidates, video.getProxyUrl());
        }
        MessageEmbed.ImageInfo image = embed.getImage();
        if (image != null) {
            add(candidates, image.getUrl());
            add(candidates, image.getProxyUrl());
        }
        return List.copyOf(candidates);
    }

    static String deriveTenorGifUrl(String mediaUrl) {
        if (mediaUrl == null || mediaUrl.isBlank()) return null;
        try {
            URI uri = URI.create(mediaUrl);
            String host = uri.getHost();
            String path = uri.getRawPath();
            if (host == null || path == null || !host.toLowerCase(Locale.ROOT)
                    .matches("media[0-9]*\\.tenor\\.(com|co)")) {
                return replaceVideoExtension(mediaUrl);
            }
            String[] segments = path.split("/");
            for (int index = 0; index < segments.length; index++) {
                if (segments[index].endsWith("AAAPo")) {
                    segments[index] = segments[index].substring(0, segments[index].length() - 5) + "AAAAC";
                }
            }
            String rebuilt = String.join("/", segments);
            int dot = rebuilt.lastIndexOf('.');
            if (dot > rebuilt.lastIndexOf('/')) rebuilt = rebuilt.substring(0, dot) + ".gif";
            String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
            return uri.getScheme() + "://" + uri.getRawAuthority() + rebuilt + query;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String replaceVideoExtension(String value) {
        int queryIndex = value.indexOf('?');
        String path = queryIndex < 0 ? value : value.substring(0, queryIndex);
        String query = queryIndex < 0 ? "" : value.substring(queryIndex);
        String lower = path.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".mp4") && !lower.endsWith(".webm")) return null;
        return path.substring(0, path.lastIndexOf('.')) + ".gif" + query;
    }

    private static void addDerivedGif(Set<String> target, String value) {
        add(target, deriveTenorGifUrl(value));
    }

    private static void add(Set<String> target, String value) {
        if (value != null && !value.isBlank()) target.add(value);
    }

    private static List<String> distinct(String... values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) add(result, value);
        return List.copyOf(result);
    }

    private static int qqFileType(String contentType) {
        if (contentType == null) return PendingMessage.FILE_TYPE_FILE;
        if (contentType.startsWith("image/")) return PendingMessage.FILE_TYPE_IMAGE;
        if (contentType.startsWith("video/")) return PendingMessage.FILE_TYPE_VIDEO;
        if (contentType.startsWith("audio/")) return PendingMessage.FILE_TYPE_VOICE;
        return PendingMessage.FILE_TYPE_FILE;
    }

    private static String commandMediaContentType(int fileType, byte[] data) {
        return MediaFormat.detectContentType(data).orElse(switch (fileType) {
            case PendingMessage.FILE_TYPE_IMAGE -> "image/png";
            case PendingMessage.FILE_TYPE_VIDEO -> "video/mp4";
            case PendingMessage.FILE_TYPE_VOICE -> "audio/ogg";
            default -> "application/octet-stream";
        });
    }

    private static void replaceBody(StringBuilder target, String value) {
        target.setLength(0);
        target.append(value);
    }

    private static boolean isDcsCommand(String text) {
        if (text == null) return false;
        String normalized = text.stripLeading().toLowerCase(Locale.ROOT);
        return normalized.equals("/dcs") || normalized.startsWith("/dcs ");
    }

    private static String displayFilename(String value) {
        return value == null || value.isBlank() ? "media" : value;
    }

    private static String firstUrl(List<String> values) {
        return values == null || values.isEmpty() ? "" : values.getFirst();
    }

    private static void appendLine(StringBuilder target, String value) {
        if (value == null || value.isBlank()) return;
        if (!target.isEmpty() && target.charAt(target.length() - 1) != '\n') target.append('\n');
        target.append(value);
    }

    public record BindResult(boolean success, String message, String guildName, String channelName) {
        static BindResult success(String guildName, String channelName) {
            return new BindResult(true, "", guildName, channelName);
        }

        static BindResult failed(String message) {
            return new BindResult(false, message, "", "");
        }
    }

    private record PreparedQqMedia(DownloadedMedia media, FileInfo uploaded) {
    }

    private static final class SerialExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        private final Executor executor;
        private Runnable active;

        private SerialExecutor(Executor executor) {
            this.executor = executor;
        }

        @Override
        public synchronized void execute(Runnable command) {
            tasks.offer(() -> {
                try {
                    command.run();
                } finally {
                    scheduleNext();
                }
            });
            if (active == null) scheduleNext();
        }

        private synchronized void scheduleNext() {
            if ((active = tasks.poll()) != null) executor.execute(active);
        }
    }
}
