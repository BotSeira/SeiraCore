package xyz.zcraft.seira.bot;

import com.google.gson.*;
import lombok.Getter;
import lombok.Setter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import xyz.zcraft.seira.bot.data.AccessToken;
import xyz.zcraft.seira.bot.data.Attachment;
import xyz.zcraft.seira.bot.data.MsgElem;
import xyz.zcraft.seira.command.AttachmentHandler;
import xyz.zcraft.seira.command.route.Router;
import xyz.zcraft.seira.config.AppConfig;
import xyz.zcraft.seira.discord.DiscordBridgeService;
import xyz.zcraft.seira.discord.QqIncomingMessage;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public class WSClient extends WebSocketClient {
    private static final Logger LOG = LogManager.getLogger(WSClient.class);
    private final Gson gson = new Gson();
    @Getter
    private final AppConfig config;
    private final Supplier<AccessToken> tokenSupplier;
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("seira-gateway-heartbeat").factory()
    );
    private final Executor eventExecutor;
    private final AtomicLong sequence = new AtomicLong(-1);
    private final Router router;
    private final AttachmentHandler attachmentHandler;
    private final DiscordBridgeService discordBridgeService;
    private volatile boolean heartbeatAcked = true;
    @Setter
    private Runnable onCloseCallback = null;

    public WSClient(
            URI serverUri,
            AppConfig config,
            Supplier<AccessToken> tokenSupplier,
            Router router,
            AttachmentHandler attachmentHandler,
            DiscordBridgeService discordBridgeService,
            Executor eventExecutor
    ) {
        super(serverUri);
        this.config = config;
        this.tokenSupplier = tokenSupplier;
        this.router = java.util.Objects.requireNonNull(router);
        this.attachmentHandler = java.util.Objects.requireNonNull(attachmentHandler);
        this.discordBridgeService = java.util.Objects.requireNonNull(discordBridgeService);
        this.eventExecutor = java.util.Objects.requireNonNull(eventExecutor);

        LOG.info("QQ Gateway WebSocket Client created");
    }

    private static String firstText(JsonObject object, String... names) {
        for (String name : names) {
            if (object.has(name) && !object.get(name).isJsonNull()) {
                String value = object.get(name).getAsString();
                if (!value.isBlank()) return value;
            }
        }
        return "QQ用户";
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        LOG.info("Gateway connected");
    }

    @Override
    public void onMessage(String message) {
        eventExecutor.execute(() -> {
            try {
                processMessage(message);
            } catch (RuntimeException e) {
                LOG.error("Failed to process gateway payload", e);
            }
        });
    }

    private void processMessage(String message) {
        JsonObject payload = gson.fromJson(message, JsonObject.class);
        updateSequence(payload.get("s"));

        int op = payload.get("op").getAsInt();
        switch (op) {
            case 10 -> onHello(payload.getAsJsonObject("d"));
            case 11 -> heartbeatAcked = true;
            case 0 -> onDispatch(payload);
            case 7, 9 -> {
                LOG.warn("Gateway requested reconnect/invalid session. closing current connection");
                close();
            }
            default -> LOG.debug("Ignored opcode {}", op);
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        LOG.warn("Gateway closed. code={}, reason={}, remote={}", code, reason, remote);
        heartbeatExecutor.shutdownNow();

        if (onCloseCallback != null) {
            onCloseCallback.run();
        }
    }

    @Override
    public void onError(Exception ex) {
        LOG.error("Gateway error", ex);
    }

    private void onHello(JsonObject data) {
        int intervalMs = data.get("heartbeat_interval").getAsInt();
        sendIdentify();
        startHeartbeat(intervalMs);
    }

    private void onDispatch(JsonObject payload) {
        String eventType = payload.get("t").getAsString();

        if ("C2C_MESSAGE_CREATE".equals(eventType)) {
            if (payload.get("d").getAsJsonObject().has("attachments")) {
                onC2CFile(payload);
            } else {
                onC2CMsg(payload);
            }
        } else if ("GROUP_AT_MESSAGE_CREATE".equals(eventType) || "GROUP_MESSAGE_CREATE".equals(eventType)) {
            onGroupMsg(payload);
        }
    }

    private void onC2CMsg(JsonObject payload) {
        JsonObject data = payload.get("d").getAsJsonObject();
        String content = data.get("content").getAsString();
        String msgId = data.get("id").getAsString();
        String openId = data.get("author").getAsJsonObject().get("user_openid").getAsString();

        final JsonArray attachments = data.getAsJsonArray("attachments");
        List<Attachment> attachmentList = new ArrayList<>();

        if (attachments != null && !attachments.isJsonNull()) {
            for (JsonElement attachmentElem : attachments) {
                JsonObject attachmentObj = attachmentElem.getAsJsonObject();
                Attachment attachment = gson.fromJson(attachmentObj, Attachment.class);
                attachmentList.add(attachment);
            }
        }

        String msgIdx = null;

        final JsonArray extArr = data.get("message_scene").getAsJsonObject().get("ext").getAsJsonArray();

        for (JsonElement elem : extArr) {
            final String str = elem.getAsString();

            if (str.startsWith("msg_idx=")) {
                msgIdx = str.substring("msg_idx=".length());
            }
        }

        List<MsgElem> msgElemList = new ArrayList<>();

        if (data.has("msg_elements")) {
            data.get("msg_elements").getAsJsonArray()
                    .forEach(elem -> msgElemList.add(gson.fromJson(elem, MsgElem.class)));
        }

        router.onPrivateMessageReceived(openId, msgId, content, msgIdx, attachmentList, msgElemList);
    }

    private void onC2CFile(JsonObject payload) {
        JsonObject data = payload.get("d").getAsJsonObject();
        String msgId = data.get("id").getAsString();
        String openId = data.get("author").getAsJsonObject().get("user_openid").getAsString();
        final JsonArray attachments = data.getAsJsonArray("attachments");
        List<Attachment> attachmentList = new ArrayList<>(attachments.size());
        for (JsonElement attachmentElem : attachments) {
            JsonObject attachmentObj = attachmentElem.getAsJsonObject();
            Attachment attachment = gson.fromJson(attachmentObj, Attachment.class);
            attachmentList.add(attachment);
        }

        attachmentHandler.handleAttachments(
                attachmentList,
                (s) -> router.sendAttachmentUploadMessage(openId, msgId, s)
        );
    }

    private void onGroupMsg(JsonObject payload) {
        JsonObject data = payload.get("d").getAsJsonObject();
        String content = data.has("content") && !data.get("content").isJsonNull()
                ? data.get("content").getAsString()
                : "";
        String msgId = data.get("id").getAsString();
        JsonObject author = data.get("author").getAsJsonObject();
        String openId = author.get("member_openid").getAsString();
        String groupId = data.get("group_openid").getAsString();

        String msgIdx = null;

        final JsonArray extArr = data.get("message_scene").getAsJsonObject().get("ext").getAsJsonArray();

        for (JsonElement elem : extArr) {
            final String str = elem.getAsString();

            if (str.startsWith("msg_idx=")) {
                msgIdx = str.substring("msg_idx=".length());
            }
        }

        List<MsgElem> msgElemList = new ArrayList<>();

        if (data.has("msg_elements")) {
            data.get("msg_elements").getAsJsonArray()
                    .forEach(elem -> msgElemList.add(gson.fromJson(elem, MsgElem.class)));
        }

        List<Attachment> attachments = parseAttachments(data);
        Map<String, String> mentions = parseMentions(data);

        if (!openId.equals(config.qq().selfId())) {
            discordBridgeService.acceptQqMessage(new QqIncomingMessage(
                    groupId,
                    openId,
                    firstText(author, "nickname", "username", "member_name", "member_openid"),
                    msgId,
                    stripSelfMention(content),
                    attachments,
                    mentions
            ));
        }

        router.onGroupMessageReceived(groupId, openId, msgId, content, msgIdx, attachments, msgElemList);
    }

    private Map<String, String> parseMentions(JsonObject data) {
        final JsonElement mentionElement = data.get("mentions");
        if (mentionElement == null || !mentionElement.isJsonArray()) {
            return Map.of();
        }

        final JsonArray asJsonArray = mentionElement.getAsJsonArray();

        if (asJsonArray == null || asJsonArray.isJsonNull()) {
            return Map.of();
        }

        Map<String, String> mentions = new java.util.HashMap<>(asJsonArray.size());

        for (JsonElement jsonElement : asJsonArray) {
            final JsonObject obj = jsonElement.getAsJsonObject();

            final String id = obj.get("id").getAsString();
            final String username = obj.get("username").getAsString();

            mentions.put(id, username);
        }

        return mentions;
    }

    private List<Attachment> parseAttachments(JsonObject data) {
        if (!data.has("attachments") || !data.get("attachments").isJsonArray()) return List.of();
        JsonArray attachments = data.getAsJsonArray("attachments");
        List<Attachment> result = new ArrayList<>(attachments.size());
        for (JsonElement element : attachments) {
            result.add(gson.fromJson(element, Attachment.class));
        }
        return List.copyOf(result);
    }

    private String stripSelfMention(String content) {
        if (content == null) return "";
        String selfAt = "<@" + config.qq().selfId() + ">";
        String normalized = content.stripLeading();
        return normalized.startsWith(selfAt)
                ? normalized.substring(selfAt.length()).stripLeading()
                : normalized;
    }

    private void sendIdentify() {
        JsonObject data = new JsonObject();
        data.addProperty("token", "QQBot " + tokenSupplier.get().token());
        /*
            GUILDS (1 << 0)
            GUILD_MEMBERS (1 << 1)
            GUILD_MESSAGES (1 << 9)    // 消息事件，仅 *私域* 机器人能够设置此 intents。
            GUILD_MESSAGE_REACTIONS (1 << 10)
            DIRECT_MESSAGE (1 << 12)
            GROUP_AND_C2C_EVENT (1 << 25)
            INTERACTION (1 << 26)
            MESSAGE_AUDIT (1 << 27)
            FORUMS_EVENT (1 << 28)  // 论坛事件，仅 *私域* 机器人能够设置此 intents。
            AUDIO_ACTION (1 << 29)
            PUBLIC_GUILD_MESSAGES (1 << 30) // 消息事件，此为公域的消息事件
         */
        data.addProperty("intents", 1 << 25 | 1 << 26 | 1 << 28 | 1 << 30);

        JsonObject payload = new JsonObject();
        payload.addProperty("op", 2);
        payload.add("d", data);

        send(payload.toString());
        LOG.info("Identify sent");
    }

    private void startHeartbeat(int intervalMs) {
        heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (!heartbeatAcked) {
                LOG.warn("Last heartbeat was not acknowledged, closing websocket");
                close();
                return;
            }
            heartbeatAcked = false;
            sendHeartbeat();
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    private void sendHeartbeat() {
        JsonObject payload = new JsonObject();
        payload.addProperty("op", 1);
        JsonElement seq = sequence.get() < 0 ? JsonNull.INSTANCE : gson.toJsonTree(sequence.get());
        payload.add("d", seq);
        send(payload.toString());
    }

    private void updateSequence(JsonElement seq) {
        if (seq != null && !seq.isJsonNull()) {
            sequence.set(seq.getAsLong());
        }
    }
}
