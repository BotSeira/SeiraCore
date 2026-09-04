package xyz.zcraft.seira.services;

import io.javalin.Javalin;
import io.javalin.http.Context;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.osu.model.User;
import xyz.zcraft.seira.api.OsuAuthApi;
import xyz.zcraft.seira.api.data.OsuToken;
import xyz.zcraft.seira.config.BindingConfig;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Owns the OAuth callback server and its pending binding requests.
 */
public final class BindingService implements AutoCloseable {
    private static final Logger LOG = LogManager.getLogger(BindingService.class);
    private static final Duration TASK_TTL = Duration.ofMinutes(20);

    private final BindingConfig config;
    private final Executor callbackExecutor;
    private final ConcurrentHashMap<String, BindingTask> bindingTasks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService taskCleaner = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("binding-task-cleaner").factory()
    );
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Javalin server;

    public BindingService(BindingConfig config, Executor callbackExecutor) {
        this.config = Objects.requireNonNull(config, "config");
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
    }

    public synchronized void start() {
        ensureOpen();
        if (started.get()) {
            return;
        }

        try {
            server = Javalin.create(javalinConfig -> javalinConfig.routes
                            .get(config.listenPath(), this::handleCallback))
                    .start(config.listenPort());
            taskCleaner.scheduleAtFixedRate(this::removeExpiredTasks, 1, 1, TimeUnit.MINUTES);
            started.set(true);
            LOG.info("Binding callback listener started on port {}", config.listenPort());
        } catch (RuntimeException e) {
            Javalin currentServer = server;
            server = null;
            if (currentServer != null) {
                currentServer.stop();
            }
            throw e;
        }
    }

    public BindingTask createBindingTask(
            String openId,
            String messageId,
            BiConsumer<User, OsuToken> onFinish
    ) {
        synchronized (this) {
            ensureStarted();
            String taskId = UUID.randomUUID().toString();
            BindingTask task = new BindingTask(taskId, openId, messageId, onFinish);
            bindingTasks.put(taskId, task);
            return task;
        }
    }

    int pendingTaskCount() {
        return bindingTasks.size();
    }

    private void handleCallback(Context ctx) {
        String state = ctx.queryParam("state");
        String code = ctx.queryParam("code");

        if (state == null || code == null) {
            LOG.warn("Received invalid binding callback with missing parameters");
            ctx.status(400).html(BindingHtmlTemplates.failure("回调 URL 无效，请重试"));
            return;
        }

        BindingTask bindingTask = bindingTasks.remove(state);
        if (bindingTask == null || bindingTask.isExpired(System.currentTimeMillis())) {
            LOG.warn("Invalid or expired binding callback state");
            ctx.status(400).html(BindingHtmlTemplates.failure("绑定请求无效或已过期"));
            return;
        }

        ctx.future(() -> CompletableFuture
                .supplyAsync(() -> exchangeCode(code), callbackExecutor)
                .thenApplyAsync(token -> loadUser(token, bindingTask), callbackExecutor)
                .thenAccept(user -> ctx.status(200).html(
                        BindingHtmlTemplates.success(user.getUsername(), String.valueOf(user.getId()))
                ))
                .exceptionally(error -> {
                    LOG.error("Error handling binding callback", error);
                    ctx.status(500).html(BindingHtmlTemplates.failure("发生了一个内部错误"));
                    return null;
                })
                .whenComplete((ignored, error) -> bindingTasks.remove(state)));
    }

    private OsuToken exchangeCode(String code) {
        OsuToken token = OsuAuthApi.getTokenFromCode(code, config.clientId(), config.clientSecret());
        if (token == null) {
            throw new IllegalStateException("Failed to exchange osu! authorization code");
        }
        return token;
    }

    private User loadUser(OsuToken token, BindingTask task) {
        User user = OsuAuthApi.getUserFromToken(token);
        if (user == null) {
            throw new IllegalStateException("Failed to load osu! user");
        }
        task.onFinish().accept(user, token);
        LOG.info("Binding successful for user {} (id {})", user.getUsername(), user.getId());
        return user;
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

    private void ensureStarted() {
        if (!started.get()) {
            throw new IllegalStateException("Binding service has not been started");
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Binding service has been closed");
        }
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        started.set(false);
        taskCleaner.shutdownNow();
        Javalin currentServer = server;
        server = null;
        if (currentServer != null) {
            try {
                currentServer.stop();
            } catch (Exception e) {
                LOG.warn("Error stopping server", e);
            }
        }
        bindingTasks.clear();
    }

    public record BindingTask(
            String taskId,
            String openId,
            String messageId,
            BiConsumer<User, OsuToken> onFinish,
            long createdAt
    ) {
        public BindingTask(String taskId, String openId, String messageId, BiConsumer<User, OsuToken> onFinish) {
            this(taskId, openId, messageId, Objects.requireNonNull(onFinish), System.currentTimeMillis());
        }

        boolean isExpired(long now) {
            return now - createdAt >= TASK_TTL.toMillis();
        }
    }
}

final class BindingHtmlTemplates {
    private static final String SUCCESS_PAGE = load("/seira-bind-success.html");
    private static final String FAILURE_PAGE = load("/seira-bind-fail.html");

    private BindingHtmlTemplates() {
    }

    static String success(String userName, String userId) {
        return SUCCESS_PAGE.replace("{{USER_NAME}}", userName).replace("{{USER_ID}}", userId);
    }

    static String failure(String detail) {
        return FAILURE_PAGE.replace("{{ERROR_DETAIL}}", detail);
    }

    private static String load(String resource) {
        try (var stream = BindingHtmlTemplates.class.getResourceAsStream(resource)) {
            return new String(Objects.requireNonNull(stream, resource + " resource not found").readAllBytes(),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load binding HTML template " + resource, e);
        }
    }
}
