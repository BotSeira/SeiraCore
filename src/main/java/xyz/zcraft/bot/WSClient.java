package xyz.zcraft.bot;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import lombok.Getter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import xyz.zcraft.command.Router;
import xyz.zcraft.config.AppConfig;
import xyz.zcraft.util.AccessToken;
import xyz.zcraft.util.ThreadHelper;

import java.net.URI;
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
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
    private final AtomicLong sequence = new AtomicLong(-1);
    private final Router router;
    private volatile boolean heartbeatAcked = true;

    public WSClient(
            URI serverUri,
            AppConfig config,
            Supplier<AccessToken> tokenSupplier,
            MessageSender messageSender
    ) {
        super(serverUri);
        this.config = config;
        this.tokenSupplier = tokenSupplier;
        this.router = new Router(messageSender, config);
        LOG.info("QQ Gateway WebSocket Client created");
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        LOG.info("Gateway connected");
    }

    @Override
    public void onMessage(String message) {
        ThreadHelper.run(() -> {
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
        });
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        LOG.warn("Gateway closed. code={}, reason={}, remote={}", code, reason, remote);
        heartbeatExecutor.shutdownNow();
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
            onC2CMsg(payload);
        } else if ("GROUP_AT_MESSAGE_CREATE".equals(eventType)) {
            onGroupMsg(payload);
        }
    }

    private void onC2CMsg(JsonObject payload) {
        JsonObject data = payload.get("d").getAsJsonObject();
        String content = data.get("content").getAsString();
        String msgId = data.get("id").getAsString();
        String openId = data.get("author").getAsJsonObject().get("user_openid").getAsString();
        router.onPrivateMessageReceived(openId, msgId, content);
    }

    private void onGroupMsg(JsonObject payload) {
        JsonObject data = payload.get("d").getAsJsonObject();
        String content = data.get("content").getAsString();
        String msgId = data.get("id").getAsString();
        String openId = data.get("author").getAsJsonObject().get("member_openid").getAsString();
        String groupId = data.get("group_openid").getAsString();
        router.onGroupMessageReceived(groupId, openId, msgId, content);
    }

    private void sendIdentify() {
        JsonObject data = new JsonObject();
        data.addProperty("token", "QQBot " + tokenSupplier.get().token());
        data.addProperty("intents", 33554432);

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
