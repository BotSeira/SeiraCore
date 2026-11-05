package xyz.zcraft.seira.agent;

import com.google.gson.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class ToolRegistry implements AutoCloseable {
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Predicate<String> administrator;
    private final Predicate<String> conversationAllowed;
    private final ConcurrentHashMap<String, Approval> approvals = new ConcurrentHashMap<>();
    private final Map<String, String> categories = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> loadedCategories = new ConcurrentHashMap<>();
    private volatile Map<String, Tool> tools = Map.of();
    private Set<String> configuredNames = Set.of();

    public ToolRegistry() {
        this(ignored -> false, ignored -> true);
    }

    public ToolRegistry(Predicate<String> administrator, Predicate<String> conversationAllowed) {
        this.administrator = administrator;
        this.conversationAllowed = conversationAllowed;
        register(new Tool("list_tool_categories", "列出可按需加载的工具分类及可用数量。", ToolSchema.object().additionalProperties(false).toJson(),
                Set.of(), Duration.ofSeconds(5), (args, context) -> {
            JsonObject result = new JsonObject();
            tools.forEach((name, tool) -> {
                String category = categories.get(name);
                if (category != null && permits(tool, context)) result.addProperty(category,
                        result.has(category) ? result.get(category).getAsInt() + 1 : 1);
            });
            return result;
        }));
        register(new Tool("load_tool_category", "按分类加载完整工具定义，下一次模型请求即可调用。先用 list_tool_categories 获取分类。",
                ToolSchema.object()
                        .property("category", ToolSchema.string())
                        .required("category")
                        .additionalProperties(false).toJson(),
                Set.of(), Duration.ofSeconds(5), (args, context) -> {
            String category = args.get("category").getAsString();
            JsonObject available = new JsonObject();
            tools.forEach((name, tool) -> {
                if (category.equals(categories.get(name)) && permits(tool, context))
                    available.addProperty(name, tool.description());
            });
            if (available.isEmpty()) throw new IllegalArgumentException("Unknown or unavailable category");
            loadedCategories.computeIfAbsent(context.conversationId(), ignored -> ConcurrentHashMap.newKeySet()).add(category);
            return available;
        }));
    }

    public static JsonArray definitions(Map<String, Tool> snapshot) {
        var result = new JsonArray();

        snapshot.values().forEach(tool -> {
            var spec = new JsonObject();
            spec.addProperty("type", "function");
            spec.addProperty("name", tool.name());
            spec.addProperty("description", tool.description());
            spec.addProperty("strict", false);
            spec.add("parameters", tool.parameters());
            result.add(spec);
        });

        return result;
    }

    private static JsonObject error(String code) {
        var error = new JsonObject();
        error.addProperty("success", false);
        error.addProperty("error", code);
        return error;
    }

    private static JsonObject error(String code, String detail) {
        var error = new JsonObject();
        error.addProperty("success", false);
        error.addProperty("error", code);
        error.addProperty("detail", detail);
        return error;
    }

    public synchronized void registerDeferred(String category, Tool tool) {
        register(tool);
        categories.put(tool.name(), category);
    }

    public void clearLoaded(String conversationId) {
        loadedCategories.remove(conversationId);
    }

    private boolean permits(Tool tool, Context context) {
        return conversationAllowed.test(context.conversationId())
                && tool.permits(context)
                && (!tool.adminOnly() || !context.participants().isEmpty() && context.participants().stream().allMatch(administrator));
    }

    public boolean decide(String token, String group, String user, boolean accept) {
        Approval approval = approvals.get(token);
        return administrator.test(user)
                && approval != null
                && approval.group().equals(group)
                && approval.decision().complete(accept);
    }

    private boolean confirm(Tool tool, JsonObject arguments, Context context) throws Exception {
        String token = UUID.randomUUID().toString();
        Approval approval = new Approval(context.conversationId(), new CompletableFuture<>());
        approvals.put(token, approval);

        try {
            String preview = arguments.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            if (preview.length() > 3000) throw new IllegalArgumentException("Confirmation preview too large");

            context.notice().accept("工具 " + tool.name() + " 请求执行，参数：\n" + preview
                    + "\n根据配置，此操作需要管理员批准喵。\n请管理员在两分钟内发送 /ai confirm " + token + " 或 /ai deny " + token);

            return approval.decision().get(120, TimeUnit.SECONDS);
        } finally {
            approvals.remove(token);
        }
    }

    public synchronized void register(Tool tool) {
        if (tools.containsKey(tool.name())) throw new IllegalArgumentException("Duplicate tool: " + tool.name());

        var next = new LinkedHashMap<>(tools);
        next.put(tool.name(), tool);
        tools = Collections.unmodifiableMap(next);
    }

    public synchronized void replaceConfigured(List<Tool> replacements) {
        var next = new LinkedHashMap<>(tools);
        configuredNames.forEach(next::remove);

        var names = new HashSet<String>();
        for (Tool tool : replacements) {
            if (next.putIfAbsent(tool.name(), tool) != null)
                throw new IllegalArgumentException("Duplicate tool: " + tool.name());
            names.add(tool.name());
        }

        configuredNames = Set.copyOf(names);
        tools = Collections.unmodifiableMap(next);
    }

    public Map<String, Tool> snapshot(Context context) {
        var result = new LinkedHashMap<String, Tool>();
        tools.forEach((name, tool) -> {
            if (permits(tool, context) && (!categories.containsKey(name)
                    || loadedCategories.getOrDefault(context.conversationId(), Set.of()).contains(categories.get(name)))) {
                result.put(name, tool);
            }
        });
        return Collections.unmodifiableMap(result);
    }

    private static final Logger agentLogger = LogManager.getLogger("AgentLogger");

    public JsonObject execute(JsonObject call, Map<String, Tool> snapshot, Context context) {
        var output = new JsonObject();
        output.addProperty("type", "function_call_output");
        output.addProperty("call_id", call.get("call_id").getAsString());

        JsonElement result;
        long started = System.nanoTime();
        Future<JsonElement> task = null;

        try {
            agentLogger.debug("Calling tool with {}", call);
            
            Tool tool = snapshot.get(call.get("name").getAsString());
            if (tool == null || !permits(tool, context)) throw new IllegalArgumentException("Tool not found, not loaded or not permitted.");

            JsonObject arguments = JsonParser.parseString(call.get("arguments").getAsString()).getAsJsonObject();
            ToolArguments.validate(tool.parameters(), arguments);
            
            if (tool.requiresConfirmation() && !confirm(tool, arguments, context)) {
                output.addProperty("output", error("CONFIRMATION_DENIED").toString());
                return output;
            }

            if (!permits(tool, context)) throw new IllegalArgumentException();

            task = executor.submit(() -> tool.handler().execute(arguments, context));
            result = task.get(tool.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (result == null) result = JsonNull.INSTANCE;
            // if (result.toString().length() > 100_000) result = error("OUTPUT_TOO_LARGE");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result = error("CANCELLED");
            agentLogger.error("Tool call cancelled", e);
        } catch (TimeoutException e) {
            result = error("TIMEOUT");
            agentLogger.error("Tool call timed out", e);
        } catch (Exception e) {
            result = error("TOOL_FAILED", e.getMessage());
            agentLogger.error("Tool call failed", e);
        } finally {
            if (task != null && !task.isDone()) task.cancel(true);
        }

        output.addProperty("output", result.toString());
        org.apache.logging.log4j.LogManager.getLogger(ToolRegistry.class).info(
                "Agent tool={} conversation={} participants={} durationMs={} error={}",
                call.get("name").getAsString(), context.conversationId(), context.participants().size(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                result.isJsonObject() && result.getAsJsonObject().has("error"));
        return output;
    }

    @Override
    public void close() {
        approvals.values().forEach(approval -> approval.decision().complete(false));
        executor.shutdownNow();
    }

    @FunctionalInterface
    public interface Handler {
        JsonElement execute(JsonObject arguments, Context context) throws Exception;
    }

    public record Context(
            String conversationId,
            Set<String> participants,
            Map<String, String> aliases,
            Consumer<String> notice
    ) {
        public Context(String conversationId, Set<String> participants) {
            this(conversationId, participants, Map.of(), ignored -> {
            });
        }

        public Context {
            participants = Set.copyOf(participants);
            aliases = Map.copyOf(aliases);
        }
    }

    public record Tool(
            String name,
            String description,
            JsonObject parameters,
            Set<String> groups,
            Duration timeout,
            Handler handler,
            boolean adminOnly,
            boolean requiresConfirmation
    ) {
        public Tool(String name, String description, JsonObject parameters, Set<String> groups, Duration timeout, Handler handler) {
            this(name, description, parameters, groups, timeout, handler, false, false);
        }

        public Tool {
            if (name == null || !name.matches("[a-zA-Z0-9_-]{1,64}"))
                throw new IllegalArgumentException("Invalid tool name");

            Objects.requireNonNull(description);
            Objects.requireNonNull(handler);

            parameters = Objects.requireNonNull(parameters).deepCopy();
            ToolArguments.checkSchema(parameters);

            groups = groups == null ? Set.of() : Set.copyOf(groups);

            if (timeout == null || timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("Tool timeout must be positive");
            }
        }

        @Override
        public JsonObject parameters() {
            return parameters.deepCopy();
        }

        boolean permits(Context context) {
            return groups.isEmpty() || groups.contains(context.conversationId());
        }
    }

    private record Approval(String group, CompletableFuture<Boolean> decision) {
    }
}
