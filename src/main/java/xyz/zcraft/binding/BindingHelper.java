package xyz.zcraft.binding;

import io.javalin.Javalin;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.config.BindingConfig;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class BindingHelper {
    private static final Logger LOG = LogManager.getLogger(BindingHelper.class);
    private static final ConcurrentHashMap<String, BindingTask> bindingTasks = new ConcurrentHashMap<>();

    public static void init(BindingConfig bindingConfig) {
        if (!bindingConfig.requireLogin()) {
            LOG.info("Required login is disabled, callback service will be disabled.");
            return;
        }

        final Javalin javalin = Javalin.create(config -> config.routes
                .get("/", ctx -> {
                    final String state = ctx.queryParam("state");
                    final String code = ctx.queryParam("code");

                    if(state == null || code == null) {
                        LOG.warn("Received invalid binding callback with missing parameters");
                        ctx.status(400).result("Missing required parameters");
                        return;
                    }

                    if(!bindingTasks.containsKey(state)) {
                        LOG.warn("Invalid binding callback with no matching state");
                        ctx.status(400).result("Missing required parameters");
                        return;
                    }

                    bindingTasks.get(state).onFinish().accept(code);
                }));

        javalin.start(bindingConfig.listenPort());

        LOG.info("Callback listener started");
    }

    public static UUID createBindingTask(String openId, String messageId, Consumer<String> onFinish) {
        UUID taskId = UUID.randomUUID();
        bindingTasks.put(taskId.toString(), new BindingTask(openId, messageId, onFinish));
        return taskId;
    }

    public static BindingTask getBindingTask(String taskId) {
        return bindingTasks.get(taskId);
    }

    public static void finishBindingTask(String taskId) {
        bindingTasks.remove(taskId);
    }

    public record BindingTask(String openId, String messageId, Consumer<String> onFinish) {}
}
