package xyz.zcraft.platform.qq;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.java_websocket.handshake.ServerHandshake;
import xyz.zcraft.config.AppConfig;
import xyz.zcraft.platform.AbstractCommandGatewayClient;
import xyz.zcraft.platform.PlatformMessageSender;
import xyz.zcraft.util.AccessToken;
import xyz.zcraft.util.ThreadHelper;

import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public class GatewayWebSocketClient extends AbstractCommandGatewayClient {
    private static final Logger LOG = LogManager.getLogger(GatewayWebSocketClient.class);
    private final Gson gson = new Gson();
    private final AppConfig config;
    private final Supplier<AccessToken> tokenSupplier;
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
    private final AtomicLong sequence = new AtomicLong(-1);
    private volatile boolean heartbeatAcked = true;

    public GatewayWebSocketClient(
            URI serverUri,
            AppConfig config,
            Supplier<AccessToken> tokenSupplier,
            PlatformMessageSender messageSender
    ) {
        super(serverUri, messageSender);
        this.config = config;
        this.tokenSupplier = tokenSupplier;
        LOG.info("QQ Gateway WebSocket Client created");
        LOG.info(config.platforms().qq().cos().isConfigured() ? "Using COS for media storage" : "COS not configured");
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
        }
    }

    private void onC2CMsg(JsonObject payload) {
        JsonObject data = payload.get("d").getAsJsonObject();
        String content = data.get("content").getAsString();
        String msgId = data.get("id").getAsString();
        String openId = data.get("author").getAsJsonObject().get("user_openid").getAsString();
        onPrivateMessageReceived(openId, msgId, content);
    }

    private void sendIdentify() {
        JsonObject data = new JsonObject();
        data.addProperty("token", "QQBot " + tokenSupplier.get().token());
        data.addProperty("intents", config.platforms().qq().intents());

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
