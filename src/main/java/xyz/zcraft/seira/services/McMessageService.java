package xyz.zcraft.seira.services;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import xyz.zcraft.seira.bot.MessageSender;
import xyz.zcraft.seira.config.McForwardConfig;
import xyz.zcraft.seira.db.McServerStore;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class McMessageService implements AutoCloseable {
    private static final Logger LOG = LogManager.getLogger(McMessageService.class);
    private static final Duration TASK_TTL = Duration.ofMinutes(20);
    private static final Gson GSON = new Gson();
    private final ConcurrentHashMap<String, BindingTask> bindingTasks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService taskCleaner = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("mc-binding-task-cleaner").factory()
    );
    private final ConcurrentHashMap<String, WebSocket> connections = new ConcurrentHashMap<>();
    private final WsServer wsServer;
    private final AtomicReference<MessageSender> senderRef;

    public McMessageService(McForwardConfig config, AtomicReference<MessageSender> senderRef) {
        this.wsServer = new WsServer(config.listenPort(), this::onWsPayload, this::onWebsocketClose);
        this.senderRef = senderRef;
    }

    public void start() {
        LOG.info("Starting McMessageService");
        wsServer.start();
        taskCleaner.scheduleAtFixedRate(this::removeExpiredTasks, 1, 1, TimeUnit.MINUTES);
    }

    @Override
    public void close() {
        taskCleaner.shutdown();
        try {
            wsServer.stop();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    public void onWebsocketClose(WebSocket conn) {
        synchronized (this) {
            for (String s : connections.keySet()) {
                if (connections.get(s) == conn) {
                    connections.remove(s);
                    break;
                }
            }
        }
    }

    public String createBindingTask(String groupId) {
        synchronized (this) {
            Set<String> toRemove = new HashSet<>();
            bindingTasks.forEach((s, bindingTask) -> {
                if (bindingTask.groupId().equals(groupId)) {
                    toRemove.add(s);
                }
            });
            toRemove.forEach(bindingTasks::remove);

            final var code = UUID.randomUUID().toString();
            bindingTasks.put(code, new BindingTask(code, groupId));
            return code;
        }
    }

    public void tryForwardMessage(String groupId, String username, String content) {
        Optional.ofNullable(McServerStore.getGroupBoundId(groupId))
                .map(connections::get)
                .ifPresent(ws -> ws.send(GSON.toJson(WsServer.Response.message("[%s] %s".formatted(username, content)))));
    }

    public void onWsPayload(String message, WebSocket conn) {
        final Payload payload = GSON.fromJson(message, Payload.class);

        switch (payload.op()) {
            case Payload.Operation.BIND -> handleMinecraftBind(payload, conn);
            case Payload.Operation.UNBIND -> handleUnbind(conn, payload);
            case Payload.Operation.AUTHENTICATE -> handleAuthenticate(conn, payload);
            case Payload.Operation.MESSAGE -> handleMessage(conn, payload);
        }
    }

    private void handleMessage(WebSocket conn, Payload payload) {
        if (!authConnect(payload.serverId(), payload.secret())) {
            conn.send(GSON.toJson(WsServer.Response.error("Invalid credentials")));
            conn.close();
            return;
        }

        final String boundGroup = McServerStore.getBoundGroup(payload.serverId(), payload.secret());
        if (boundGroup == null) return;

        final MessageSender messageSender = senderRef.get();
        if (messageSender == null) return;

        messageSender.sendGroupMarkdown(boundGroup, payload.data().get("content").getAsString());
    }

    private void handleAuthenticate(WebSocket conn, Payload payload) {
        if (authConnect(payload.serverId(), payload.secret())) {
            synchronized (this) {
                connections.put(payload.serverId(), conn);
                conn.send(GSON.toJson(WsServer.Response.ready()));
            }
        } else {
            conn.send(GSON.toJson(WsServer.Response.error("Invalid credentials")));
        }
    }

    private void handleUnbind(WebSocket conn, Payload payload) {
        if (authConnect(payload.serverId(), payload.secret())) {
            synchronized (this) {
                connections.remove(payload.serverId(), conn);
            }
            McServerStore.unbindGroup(McServerStore.getBoundGroup(payload.serverId(), payload.secret()));
            conn.send(GSON.toJson(WsServer.Response.unbound()));
            conn.close();
        } else {
            conn.send(GSON.toJson(WsServer.Response.error("Invalid credentials")));
            conn.close();
        }
    }

    public boolean authConnect(String serverId, String secret) {
        return McServerStore.validateSecret(serverId, secret);
    }

    public void handleMinecraftBind(Payload payload, WebSocket conn) {
        handleMinecraftBind(payload.serverId(), payload.secret(), payload.data().get("code").getAsString(), conn);
    }

    private void handleMinecraftBind(String serverId, String secret, String taskId, WebSocket conn) {
        synchronized (this) {
            if (!bindingTasks.containsKey(taskId)) {
                return;
            }

            final BindingTask bindingTask = bindingTasks.get(taskId);

            if (bindingTask.isExpired(System.currentTimeMillis())) {
                return;
            }

            McServerStore.bindGroup(bindingTask.groupId(), serverId, secret);

            bindingTasks.remove(taskId);

            connections.put(serverId, conn);

            conn.send(GSON.toJson(WsServer.Response.ready()));
        }
    }

    private void removeExpiredTasks() {
        long now = System.currentTimeMillis();
        bindingTasks.entrySet().removeIf(entry -> {
            boolean expired = entry.getValue().isExpired(now);
            if (expired) {
                LOG.info("Removing expired binding task {}", entry.getKey());
            }
            return expired;
        });
    }

    public record BindingTask(
            String code,
            String groupId,
            long createdAt
    ) {
        public BindingTask(String code, String groupId) {
            this(code, groupId, System.currentTimeMillis());
        }

        boolean isExpired(long now) {
            return now - createdAt >= TASK_TTL.toMillis();
        }
    }

    public record Payload(
            int op,
            String serverId,
            String secret,
            JsonObject data
    ) {
        public static class Operation {
            public static final int BIND = 1;
            public static final int UNBIND = 2;
            public static final int AUTHENTICATE = 3;
            public static final int MESSAGE = 4;
        }
    }
}

class WsServer extends WebSocketServer {
    private static final Logger LOG = LogManager.getLogger(WsServer.class);

    private final BiConsumer<String, WebSocket> handler;
    private final Consumer<WebSocket> onCloseHandler;

    public WsServer(int port, BiConsumer<String, WebSocket> handler, Consumer<WebSocket> onClose) {
        super(new InetSocketAddress(port));
        this.handler = handler;
        this.onCloseHandler = onClose;
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        LOG.debug("Websocket connection opened: {}", conn);
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        LOG.debug("Websocket connection closed: {} - {}", code, reason);
        if (onCloseHandler != null) {
            onCloseHandler.accept(conn);
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        LOG.debug("Websocket message received: {}", message);
        handler.accept(message, conn);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        LOG.error("Error in websocket connection", ex);
    }

    @Override
    public void onStart() {
        LOG.info("Websocket Server Started");
    }

    public record Response(
            int code,
            JsonObject data
    ) {
        public static Response ready() {
            return new Response(Code.READY, null);
        }

        public static Response error(String message) {
            JsonObject data = new JsonObject();
            data.addProperty("error", message);
            return new Response(Code.ERROR, data);
        }

        public static Response message(String content) {
            JsonObject data = new JsonObject();
            data.addProperty("content", content);
            return new Response(Code.MESSAGE, data);
        }

        public static Response unbound() {
            return new Response(Code.UNBOUND, null);
        }

        public static class Code {
            public static final int ERROR = -1;
            public static final int READY = 0;
            public static final int UNBOUND = 1;
            public static final int MESSAGE = 2;
        }
    }
}

