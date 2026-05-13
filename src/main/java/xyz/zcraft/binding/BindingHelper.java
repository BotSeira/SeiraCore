package xyz.zcraft.binding;

import io.javalin.Javalin;
import io.javalin.http.Context;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.api.OsuAuthApi;
import xyz.zcraft.config.BindingConfig;
import xyz.zcraft.data.OsuToken;
import xyz.zcraft.data.OsuUser;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class BindingHelper {
    private static final Logger LOG = LogManager.getLogger(BindingHelper.class);
    private static final ConcurrentHashMap<String, BindingTask> bindingTasks = new ConcurrentHashMap<>();

    public static void init(BindingConfig bindingConfig) {
        if (!bindingConfig.requireLogin()) {
            LOG.info("Required login is disabled, callback service will be disabled.");
            return;
        }

        final Javalin javalin = Javalin.create(config -> config.routes
                .get(bindingConfig.listenPath(), ctx -> handleCallback(ctx, bindingConfig)));

        javalin.start(bindingConfig.listenPort());

        LOG.info("Callback listener started");
    }

    private static void handleCallback(Context ctx, BindingConfig bindingConfig) {
        final String state = ctx.queryParam("state");
        final String code = ctx.queryParam("code");

        if (state == null || code == null) {
            LOG.warn("Received invalid binding callback with missing parameters");
            ctx.status(400).result("Missing required parameters");
            return;
        }

        if (!bindingTasks.containsKey(state)) {
            LOG.warn("Invalid binding callback with no matching state");
            ctx.status(400).result("Invalid or expired state");
            return;
        }

        final BindingTask bindingTask = bindingTasks.get(state);

        ctx.future(() -> CompletableFuture.supplyAsync(() ->
                        OsuAuthApi.getTokenFromCode(code, bindingConfig.clientId(), bindingConfig.clientSecret())
                )
                .thenApply(token -> {
                    if (token == null) throw new RuntimeException("Failed to get token from code");
                    return token;
                })
                .thenApplyAsync(token -> {
                    var user = OsuAuthApi.getUserFromToken(token);
                    if (user == null) throw new RuntimeException("Failed to get user from token");

                    bindingTask.onFinish().accept(user, token);
                    return user;
                })
                .thenAccept(user -> ctx.html("""
                        <!DOCTYPE html>
                        <html lang="zh">
                            <head>
                                <meta charset="UTF-8">
                                <title>绑定成功</title>
                            </head>
                            <body>
                                <h1>成功绑定至玩家%s(%d)！</h1>
                                <p>你可以关闭这个页面了。</p>
                            </body>
                        </html>
                        """.formatted(user.username(), user.id())))
                .exceptionally(e -> {
                    LOG.error("Error handling binding callback", e);
                    ctx.status(500).html("""
                            <!DOCTYPE html>
                            <html lang="zh">
                                <head>
                                    <meta charset="UTF-8">
                                    <title>绑定失败</title>
                                </head>
                                <body>
                                    <h1>绑定失败！</h1>
                                    <p>发生了一个错误，请重试。</p>
                                </body>
                            </html>
                            """);
                    return null;
                })
                .whenComplete((_, _) -> bindingTasks.remove(state)));
    }

    public static BindingTask createBindingTask(String openId, String messageId, BiConsumer<OsuUser, OsuToken> onFinish) {
        UUID taskId = UUID.randomUUID();
        final BindingTask task = new BindingTask(taskId.toString(), openId, messageId, onFinish);
        bindingTasks.put(taskId.toString(), task);
        return task;
    }

    public record BindingTask(String taskId, String openId, String messageId, BiConsumer<OsuUser, OsuToken> onFinish) {
    }
}
