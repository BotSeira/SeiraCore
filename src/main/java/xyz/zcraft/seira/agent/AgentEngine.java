package xyz.zcraft.seira.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.seira.bot.data.Attachment;
import xyz.zcraft.seira.command.reply.ReplyFactory;
import xyz.zcraft.seira.db.UserDataStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class AgentEngine implements AutoCloseable {
    private static final Logger LOG = LogManager.getLogger(AgentEngine.class);
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
        if (session.received.size() > 1000) session.received.remove(session.received.iterator().next());

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

    private static final Logger agentLogger = LogManager.getLogger("AgentLogger");

    private void run(String id, Session session) {
        LOG.info("Running session in conversation: {}", id);
        JsonArray input = new JsonArray();
        Consumer<String> reply = ignored -> {
        };
        int rounds = 0;
        Set<String> authors = new HashSet<>();

        agentLogger.debug("Running session with msg {} and user {}", session.inbox, session.participants);
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

                var context = new ToolRegistry.Context(id, authors, session.participants, reply);
                var snapshot = tools.snapshot(context);
                Consumer<String> destination = reply;

                store.clear(id);

                agentLogger.info("Creating agent response with input: {}", input);
                JsonObject response = client.create(
                        session.previousId,
                        instructions.get(),
                        input,
                        ToolRegistry.definitions(snapshot),
                        item -> {
                            StringBuilder text = new StringBuilder();
                            for (var part : item.getAsJsonArray("content")) {
                                JsonObject content = part.getAsJsonObject();
                                if (content.has("text")) {
                                    text.append(content.get("text").getAsString());
                                } else if (content.has("refusal")) {
                                    text.append(content.get("refusal").getAsString());
                                }
                            }
                            if (!text.isEmpty())
                                destination.accept(renderMentions(text.toString(), session.participants,
                                        uid -> UserDataStore.findGroupOpenIdByUid(id, uid)));
                        }
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
                        input.add(tools.execute(call, snapshot, context));
                    }
                }

                if (input.isEmpty()) {
                    store.save(id, session.previousId, session.participants);
                    rounds = 0;
                    authors.clear();
                }
            }
        } catch (Exception error) {
            session.previousId = null;
            LOG.warn("Agent conversation failed: {}", id, error);

            try {
                reply.accept("本次对话出现错误未能完成，请重新发送请求喵。");
            } catch (RuntimeException ignored) {
            }
        } finally {
            synchronized (this) {
                tools.clearLoaded(id);
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
            String id, String senderId, String senderName, String text,
            List<Attachment> attachments, long timestamp, Consumer<String> reply) {
        public Message {
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }

        JsonObject toInput(String alias, Map<String, String> participants, Map<String, String> names) {
            var metadata = new JsonObject();
            metadata.addProperty("message_id", id);
            metadata.addProperty("sender_id", alias);
            metadata.addProperty("sender_name", senderName);
            metadata.addProperty("timestamp", timestamp);
            metadata.addProperty("text", text);
            metadata.addProperty("context_only", reply == null);
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
                    image.addProperty("image_url", attachment.url());
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

    private static final class Session {
        final ArrayDeque<Message> inbox = new ArrayDeque<>();
        final Map<String, String> participants = new LinkedHashMap<>();
        final Map<String, String> names = new HashMap<>();
        final Set<String> received = new LinkedHashSet<>();
        boolean running;
        long lastUsed = System.currentTimeMillis();
        String previousId;
    }
}
