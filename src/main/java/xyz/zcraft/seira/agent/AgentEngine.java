package xyz.zcraft.seira.agent;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.seira.bot.data.Attachment;
import xyz.zcraft.seira.bot.data.msg.PendingMessage;
import xyz.zcraft.seira.command.reply.ReplyFactory;
import xyz.zcraft.seira.db.UserDataStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class AgentEngine implements AutoCloseable {
    private static final Logger LOG = LogManager.getLogger(AgentEngine.class);
    private static final Logger requestLogger = LogManager.getLogger("AgentRequestsLogger");
    private static final Logger agentLogger = LogManager.getLogger("AgentLogger");
    private static final Gson GSON = new Gson();
    private static final int MAX_HISTORY = 32;
    private final Map<String, Session> sessions = new HashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ResponsesClient client;
    private final ToolRegistry tools;
    private final Supplier<String> instructions;
    private final ConversationStore store;
    private final Predicate<String> allowed;
    private boolean closed;

    public AgentEngine(
            ResponsesClient client, ToolRegistry tools,
            Supplier<String> instructions, ConversationStore store, Predicate<String> allowed
    ) {
        this.client = Objects.requireNonNull(client);
        this.tools = Objects.requireNonNull(tools);
        this.instructions = Objects.requireNonNull(instructions);
        this.store = Objects.requireNonNull(store);
        this.allowed = Objects.requireNonNull(allowed);
    }

    static String renderMentions(String text, Map<String, String> participants, LongFunction<Optional<String>> findGroupMember) {
        String safe = text.replaceAll("(?is)<qqbot-at-user\\b[^>]*>", "");
        var matcher = java.util.regex.Pattern.compile("\\[\\[(?:at:(u[0-9]+)|osuat:([0-9]+))]]").matcher(safe);
        StringBuilder output = new StringBuilder();
        Map<Long, Optional<String>> members = new HashMap<>();

        while (matcher.find()) {
            String openId;
            String fallback = "";
            if (matcher.group(2) != null) {
                String uidText = matcher.group(2);
                long uid;
                try {
                    uid = Long.parseLong(uidText);
                } catch (NumberFormatException e) {
                    matcher.appendReplacement(output, java.util.regex.Matcher.quoteReplacement(matcher.group()));
                    continue;
                }
                openId = members.computeIfAbsent(uid, findGroupMember::apply).orElse(null);
                fallback = ReplyFactory.cmd("/ux " + uidText, uidText);
            } else {
                openId = participants.entrySet().stream()
                        .filter(e -> e.getValue().equals(matcher.group(1)))
                        .map(Map.Entry::getKey)
                        .findFirst()
                        .orElse(null);
            }

            String replacement = (openId != null && openId.matches("[A-Za-z0-9_-]+")
                    ? "<qqbot-at-user id=\"" + openId + "\" />"
                    : fallback);

            matcher.appendReplacement(output, java.util.regex.Matcher.quoteReplacement(replacement));
        }

        matcher.appendTail(output);
        return output.toString();
    }

    private static boolean isImageFilename(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).matches(".*\\.(png|jpe?g|webp|gif)$");
    }

    private static void enrichUsers(JsonObject metadata, Message message, Map<String, String> participants,
                                    Map<String, String> names) {
        var related = new LinkedHashSet<String>();
        related.add(message.senderId());

        var matcher = java.util.regex.Pattern.compile("<@([A-Za-z0-9_-]+)>")
                .matcher(message.text() == null ? "" : message.text());
        StringBuilder normalized = new StringBuilder();
        while (matcher.find()) {
            String openId = matcher.group(1);
            related.add(openId);
            String alias = participants.computeIfAbsent(openId, ignored -> "u" + (participants.size() + 1));
            matcher.appendReplacement(normalized, "[[at:" + alias + "]]");
        }
        matcher.appendTail(normalized);
        metadata.addProperty("text", normalized.toString());

        var users = new JsonArray();
        for (String openId : related) {
            var user = new JsonObject();
            user.addProperty("id", participants.get(openId));
            user.addProperty("nickname", openId.equals(message.senderId()) ? message.senderName() : names.get(openId));
            Long uid = UserDataStore.findBoundUid(openId);
            user.addProperty("osu_uid", uid);
            user.addProperty("osu_username", uid == null ? null : UserDataStore.findUsername(uid).orElse(null));
            users.add(user);
        }
        metadata.add("users", users);
    }

    static JsonArray rebuildInput(JsonArray history, JsonArray transcript, JsonArray pending) {
        var rebuilt = history.deepCopy();
        var notice = new JsonObject();
        notice.addProperty("role", "user");
        notice.addProperty("content", "[会话恢复：较早历史仅保留文字，旧图片可能不可用。以下当前任务的工具调用与结果已执行，请继续处理，不要重复执行已完成的操作。]");
        rebuilt.add(notice);
        rebuilt.addAll(transcript.deepCopy());
        rebuilt.addAll(pending.deepCopy());
        return rebuilt;
    }

    private static JsonObject textHistory(JsonObject item) {
        var result = new JsonObject();
        if (item.has("type") && item.get("type").isJsonPrimitive()
                && "reasoning".equals(item.get("type").getAsString())) {
            // Skipping reasoning.
            return null;
        }
        String role = item.has("role") ? item.get("role").getAsString() : "user";
        result.addProperty("role", role.equals("assistant") ? "assistant" : "user");
        StringBuilder text = new StringBuilder();
        if (item.has("content")) {
            if (item.get("content").isJsonArray()) {
                for (var part : item.getAsJsonArray("content")) {
                    var content = part.getAsJsonObject();
                    if (content.has("text")) text.append(content.get("text").getAsString()).append('\n');
                    else if (content.has("image_url")) text.append("[历史图片已省略]\n");
                }
            } else text.append(item.get("content").getAsString());
//        } else if (item.has("type") && item.get("type").getAsString().startsWith("function_call")) {
            // ignoring
//            text.append("[工具执行记录，仅供上下文参考] ").append(item);
//        } else {
            // ignoring
//            text.append("[非文本输出已省略]");
        }
        result.addProperty("content", text.substring(0, Math.min(text.length(), 8192)));
        return result;
    }

    public synchronized void accept(String conversationId, Message message) {
        if (closed) throw new IllegalStateException("Agent is closed");

        long now = System.currentTimeMillis();
        sessions.values().removeIf(session -> !session.running && now - session.lastUsed > 86_400_000L);

        Session session = session(conversationId);

        if (message.senderName() != null) session.names.put(message.senderId(), message.senderName());

        enqueue(conversationId, message, session, now);
    }

    private Session session(String conversationId) {
        return sessions.computeIfAbsent(conversationId, id -> {
            Session loaded = new Session();

            try {
                var saved = store.load(id);
                loaded.previousId = saved.responseId();
                loaded.participants.putAll(saved.participants());
                saved.history().forEach(item -> loaded.history.add(item.getAsJsonObject()));

                while (loaded.history.size() > MAX_HISTORY) {
                    loaded.history.removeFirst();
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return loaded;
        });
    }

    public synchronized void rememberNames(String groupId, Map<String, String> names) {
        var session = session(groupId);
        names.forEach((id, name) -> {
            if (id != null && name != null && !name.isBlank()) session.names.put(id, name);
        });
    }

    private void enqueue(String conversationId, Message message, Session session, long now) {
        if (message.id() != null && session.received.contains(message.id())) return;
        if (session.inbox.size() >= 100) {
            var history = session.inbox.stream().filter(item -> item.reply() == null).findFirst();
            if (history.isPresent()) session.inbox.remove(history.get());
            else if (message.reply() == null) return;
            else throw new RejectedExecutionException("Conversation inbox is full");
        }

        if (message.id() != null) session.received.add(message.id());
        if (session.received.size() > 100) session.received.remove(session.received.iterator().next());

        session.inbox.add(message);
        session.lastUsed = now;

        if (!session.running && message.reply() != null) {
            session.running = true;
            workers.execute(() -> run(conversationId, session));
        }
    }

    public synchronized Set<String> activeConversationIds() {
        var active = new HashSet<String>();
        sessions.forEach((id, session) -> {
            if (session.running) active.add(id);
        });
        return Set.copyOf(active);
    }

    public synchronized boolean reset(String id) {
        Session session = sessions.get(id);
        if (session != null && session.running) return false;

        try {
            store.clear(id);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        sessions.remove(id);
        return true;
    }

    private void run(String id, Session session) {
        LOG.info("Running session in conversation: {}", id);
        JsonArray input = new JsonArray();
        Consumer<PendingMessage> reply = ignored -> {
        };
        int rounds = 0;
        boolean recovered = false;
        var transcript = new JsonArray();
        var baseHistory = new JsonArray();
        session.history.forEach(baseHistory::add);
        var executed = new HashMap<String, JsonObject>();
        Set<String> authors = new HashSet<>();

        final UUID sessionUuid = UUID.randomUUID();
        agentLogger.debug("Running session with id {} {}", id, sessionUuid);
        requestLogger.debug("SESSION {} BODY {}", sessionUuid, GSON.toJsonTree(session));
        try {
            while (!Thread.currentThread().isInterrupted()) {
                synchronized (this) {
                    if (input.isEmpty() && session.inbox.stream().noneMatch(item -> item.reply() != null)) return;

                    Message current;
                    while ((current = session.inbox.poll()) != null) {
                        if (current.reply() != null) authors.add(current.senderId());
                        String alias = session.participants.computeIfAbsent(current.senderId(), ignored -> "u" + (session.participants.size() + 1));

                        input.add(current.toInput(alias, session.participants, session.names));
                        if (current.reply() != null) reply = current.reply();
                    }

                    if (input.isEmpty()) {
                        return;
                    }
                }

                if (++rounds > 512) throw new IllegalStateException("Agent response limit reached");
                if (!allowed.test(id)) throw new CancellationException("Conversation disabled");

                var context = new ToolRegistry.Context(id, authors, session.participants, session.names, reply);
                var snapshot = tools.snapshot(context);
                Consumer<PendingMessage> destination = reply;

                store.clear(id);

                var delivered = new AtomicBoolean();
                Consumer<String> listener = text -> {
                    delivered.set(true);
                    destination.accept(PendingMessage.ofMarkdownRaw(renderMentions(text, session.participants, uid -> UserDataStore.findGroupOpenIdByUid(id, uid))));
                };
                JsonObject response;
                JsonArray requestInput = input;
                String previousId = session.localContext ? null : session.previousId;
                if (session.localContext) {
                    requestInput = rebuildInput(baseHistory, transcript, input);
                } else if (previousId == null && !session.history.isEmpty()) {
                    requestInput = new JsonArray();
                    session.history.forEach(requestInput::add);
                    requestInput.addAll(input);
                }
                try {
                    response = client.create(
                            previousId,
                            instructions.get(),
                            requestInput,
                            ToolRegistry.definitions(snapshot),
                            listener,
                            sessionUuid
                    );
                } catch (IOException error) {
                    agentLogger.error("Session {} error creating response", sessionUuid, error);
                    String detail = error.getMessage();
                    boolean missing = error instanceof ResponsesClient.ResponseException failure
                            && failure.previousResponseMissing();
                    if (missing) session.localContext = true;
                    boolean expiredImage = detail != null && detail.contains("422")
                            && detail.contains("Failed to fetch media from URL") && detail.contains("image_url");
                    if (recovered || delivered.get() || session.previousId == null || (!missing && !expiredImage))
                        throw error;
                    if (!allowed.test(id) || Thread.currentThread().isInterrupted()) throw error;
                    recovered = true;
                    session.previousId = null;
                    var rebuilt = rebuildInput(baseHistory, transcript, input);
                    agentLogger.warn("Session {} rebuilding missing or expired response chain for {}", sessionUuid, id);
                    response = client.create(null, instructions.get(), rebuilt,
                            ToolRegistry.definitions(snapshot), listener, sessionUuid);
                }
                transcript.addAll(input.deepCopy());
                for (var value : response.getAsJsonArray("output")) {
                    var item = value.getAsJsonObject();
                    String type = item.get("type").getAsString();
                    if (type.equals("message") || type.equals("function_call")
                            || (type.equals("reasoning") && item.has("encrypted_content") && !item.get("encrypted_content").isJsonNull()))
                        transcript.add(item.deepCopy());
                }
                for (var value : input) {
                    final JsonObject e = textHistory(value.getAsJsonObject());
                    if (e == null) continue;
                    session.history.add(e);
                }
                for (var value : response.getAsJsonArray("output")) {
                    final JsonObject e = textHistory(value.getAsJsonObject());
                    if (e == null) continue;
                    session.history.add(e);
                }
                while (session.history.size() > MAX_HISTORY) session.history.removeFirst();

                requestLogger.debug("SESSION {} RESPONSE {}", sessionUuid, response);
                agentLogger.info("Session {} ended for {}", sessionUuid, id);
                agentLogger.info("Updated response id for {} : {} -> {}",
                        id, session.previousId, response.get("id").getAsString()
                );
                session.previousId = response.get("id").getAsString();

                input = new JsonArray();
                var callIds = new HashSet<String>();
                for (var item : response.getAsJsonArray("output")) {
                    JsonObject call = item.getAsJsonObject();
                    if ("function_call".equals(call.get("type").getAsString()) && callIds.add(call.get("call_id").getAsString())) {
                        if (Thread.currentThread().isInterrupted() || !allowed.test(id)) {
                            throw new CancellationException("Conversation stopped");
                        }
                        String callId = call.get("call_id").getAsString();
                        JsonObject result = executed.get(callId);
                        if (result == null) {
                            result = tools.execute(call, snapshot, context, sessionUuid);
                            executed.put(callId, result);
                        }
                        input.add(result);
                    }
                }

                if (input.isEmpty()) {
                    store.save(id, session.previousId, session.participants, session.history);
                    rounds = 0;
                    recovered = false;
                    transcript = new JsonArray();
                    baseHistory = new JsonArray();
                    session.history.forEach(baseHistory::add);
                    executed.clear();
                    authors.clear();
                }
            }
        } catch (Exception error) {
            session.previousId = null;
            agentLogger.error("Agent conversation failed: {} {}", id, sessionUuid, error);
            LOG.warn("Agent conversation failed: {}", id, error);

            try {
                reply.accept(PendingMessage.ofMarkdownRaw("本次对话出现错误未能完成，请重新发送请求喵。\n>" + error.getMessage()));
            } catch (RuntimeException ignored) {
            }
        } finally {
            synchronized (this) {
                session.running = false;
                session.lastUsed = System.currentTimeMillis();

                if (!closed && session.inbox.stream().anyMatch(item -> item.reply() != null)) {
                    session.running = true;
                    workers.execute(() -> run(id, session));
                }
            }
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        workers.shutdownNow();
        tools.close();
    }

    public record Message(
            String id, String senderId, String senderName, String text, String ref,
            List<Attachment> attachments, long timestamp, Consumer<PendingMessage> reply) {
        public Message {
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }

        JsonObject toInput(String alias, Map<String, String> participants, Map<String, String> names) {
            var metadata = new JsonObject();
//            metadata.addProperty("message_id", id);
            metadata.addProperty("id", alias);
            metadata.addProperty("name", senderName);
            String time = Instant.ofEpochMilli(System.currentTimeMillis())
                    .atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("HH:mm"));
            metadata.addProperty("time", time);
            metadata.addProperty("quote", ref);
            metadata.addProperty("text", text);
//            metadata.addProperty("context_only", reply == null);
            if (participants != null) enrichUsers(metadata, this, participants, names);

            var item = new JsonObject();
            item.addProperty("role", "user");
            if (attachments.isEmpty()) {
                item.addProperty("content", metadata.toString());
                return item;
            }

            var content = new JsonArray();
            var inputText = new JsonObject();
            inputText.addProperty("type", "input_text");
            inputText.addProperty("text", metadata.toString());
            content.add(inputText);

            for (Attachment attachment : attachments) {
                String type = attachment.contentType() == null
                        ? ""
                        : attachment.contentType().split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
                if (type.startsWith("image/")) {
                    var image = new JsonObject();
                    image.addProperty("type", "input_image");
                    String dataUrl = ImageCache.dataUrl(attachment);
                    if (dataUrl == null) {
                        var missing = new JsonObject();
                        missing.addProperty("type", "input_text");
                        missing.addProperty("text", "[图片附件已不可用，请向用户说明，不能猜测图片内容]");
                        content.add(missing);
                        continue;
                    }
                    image.addProperty("image_url", dataUrl);
                    image.addProperty("detail", "auto");
                    content.add(image);
                }
//                else if (type.isEmpty() || type.equals("application/octet-stream")) {
//                    String url = attachment.url().trim();
//                    if (url.startsWith("//")) url = "https:" + url;
//                    URI uri = URI.create(url);
//                    if (isImageFilename(attachment.filename()) || isImageFilename(uri.getPath())) {
//                        var image = new JsonObject();
//                        image.addProperty("type", "input_image");
//                        image.addProperty("image_url", uri.toASCIIString());
//                        image.addProperty("detail", "auto");
//                        content.add(image);
//                    }
//                }
            }
            item.add("content", content);
            return item;
        }
    }

    private static final class Session {
        final ArrayDeque<JsonObject> history = new ArrayDeque<>();
        final ArrayDeque<Message> inbox = new ArrayDeque<>();
        /**
         * Map of participant's open id and alias.
         */
        final Map<String, String> participants = new LinkedHashMap<>();
        /**
         * Map of participant's open id and username.
         */
        final Map<String, String> names = new HashMap<>();
        final Set<String> received = new LinkedHashSet<>();
        boolean running;
        boolean localContext;
        long lastUsed = System.currentTimeMillis();
        String previousId;
    }
}
