package xyz.zcraft.seira.agent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.seira.bot.data.Attachment;
import xyz.zcraft.seira.bot.data.msg.PendingMessage;
import xyz.zcraft.seira.command.reply.ReplyFactory;
import xyz.zcraft.seira.config.AgentConfig;
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
    private static final int MAX_HISTORY = 32;
    private final Map<String, Session> sessions = new HashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ResponsesClient client;
    private final ToolRegistry tools;
    private final Supplier<String> instructions;
    private final ConversationStore store;
    private final Predicate<String> allowed;
    private boolean closed;
    private final AgentConfig config;

    public AgentEngine(
            AgentConfig config, ResponsesClient client, ToolRegistry tools,
            Supplier<String> instructions, ConversationStore store, Predicate<String> allowed
    ) {
        this.client = Objects.requireNonNull(client);
        this.tools = Objects.requireNonNull(tools);
        this.instructions = Objects.requireNonNull(instructions);
        this.store = Objects.requireNonNull(store);
        this.allowed = Objects.requireNonNull(allowed);
        this.config = Objects.requireNonNull(config);
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

    private static AgentInput.UserMessage toInput(Message message, String alias, Map<String, String> participants,
                                                  Map<String, String> names) {
        String time = Instant.ofEpochMilli(System.currentTimeMillis())
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("HH:mm"));
        var related = new LinkedHashSet<String>();
        related.add(message.senderId());

        var matcher = java.util.regex.Pattern.compile("<@([A-Za-z0-9_-]+)>")
                .matcher(message.text() == null ? "" : message.text());
        StringBuilder normalized = new StringBuilder();
        while (matcher.find()) {
            String openId = matcher.group(1);
            related.add(openId);
            String mentionedAlias = participants.computeIfAbsent(openId, ignored -> "u" + (participants.size() + 1));
            matcher.appendReplacement(normalized, "[[at:" + mentionedAlias + "]]");
        }
        matcher.appendTail(normalized);
        var users = new ArrayList<AgentInput.User>();
        for (String openId : related) {
            String nickname = openId.equals(message.senderId()) ? message.senderName() : names.get(openId);
            Long uid = UserDataStore.findBoundUid(openId);
            String osuUsername = uid == null ? null : UserDataStore.findUsername(uid).orElse(null);
            users.add(new AgentInput.User(participants.get(openId), nickname, uid, osuUsername));
        }
        return new AgentInput.UserMessage(alias, message.senderName(), time, message.ref(), normalized.toString(),
                List.copyOf(users), message.attachments());
    }

    static List<AgentInput> rebuildInput(List<AgentInput.History> history, List<AgentInput> transcript,
                                         List<AgentInput> pending) {
        var rebuilt = new ArrayList<AgentInput>(history);
        rebuilt.add(new AgentInput.Text("user", "[会话恢复：较早历史仅保留文字，旧图片可能不可用。以下当前任务的工具调用与结果已执行，请继续处理，不要重复执行已完成的操作。]"));
        rebuilt.addAll(transcript);
        rebuilt.addAll(pending);
        return rebuilt;
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
                loaded.history.addAll(saved.history());

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

    public synchronized boolean resetAll() {
        try {
            store.clearAll();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        sessions.clear();
        return true;
    }

    private void run(String id, Session session) {
        LOG.info("Running session in conversation: {}", id);
        var input = new ArrayList<AgentInput>();
        Consumer<PendingMessage> reply = ignored -> {
        };
        int rounds = 0;
        boolean recovered = false;
        var transcript = new ArrayList<AgentInput>();
        var baseHistory = new ArrayList<>(session.history);
        var executed = new HashMap<String, AgentInput.ToolResult>();
        Set<String> authors = new HashSet<>();

        final UUID sessionUuid = UUID.randomUUID();
        agentLogger.debug("Running session with id {} {}", id, sessionUuid);
        requestLogger.debug("SESSION {} previousId={} historySize={} inboxSize={}",
                sessionUuid, session.previousId, session.history.size(), session.inbox.size());
        try {
            while (!Thread.currentThread().isInterrupted()) {
                synchronized (this) {
                    if (input.isEmpty() && session.inbox.stream().noneMatch(item -> item.reply() != null)) return;

                    Message current;
                    while ((current = session.inbox.poll()) != null) {
                        if (current.reply() != null) authors.add(current.senderId());
                        String alias = session.participants.computeIfAbsent(current.senderId(), ignored -> "u" + (session.participants.size() + 1));

                        input.add(toInput(current, alias, session.participants, session.names));
                        if (current.reply() != null) reply = current.reply();
                    }

                    if (input.isEmpty()) {
                        return;
                    }
                }

                if (++rounds > 512) throw new IllegalStateException("Agent response limit reached");
                if (!allowed.test(id)) throw new CancellationException("Conversation disabled");

                var context = new ToolRegistry.ToolContext(id, authors, session.participants, session.names, reply);
                var snapshot = tools.snapshot(context);
                Consumer<PendingMessage> destination = reply;

                store.clear(id);

                var delivered = new AtomicBoolean();
                Consumer<String> listener = text -> {
                    delivered.set(true);
                    destination.accept(PendingMessage.ofMarkdownRaw(renderMentions(text, session.participants, uid -> UserDataStore.findGroupOpenIdByUid(id, uid))));
                };
                ResponsesClient.Response response;
                List<AgentInput> requestInput = input;
                String previousId = (session.localContext || !config.usePreviousResponseId()) ? null : session.previousId;
                if (session.localContext) {
                    requestInput = rebuildInput(baseHistory, transcript, input);
                } else if (previousId == null && !session.history.isEmpty()) {
                    requestInput = new ArrayList<>(session.history);
                    requestInput.addAll(input);
                }
                try {
                    response = client.create(
                            previousId,
                            instructions.get(),
                            requestInput,
                            snapshot,
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
                            snapshot, listener, sessionUuid);
                }
                transcript.addAll(input);
                for (var item : input) {
                    if (item instanceof AgentInput.UserMessage message) {
                        session.history.add(new AgentInput.UserHistory(message));
                    }
                }
                for (var item : response.output()) {
                    if (item.replayable()) transcript.add(item);
                    if (item.history() != null) session.history.add(item.history());
                }
                while (session.history.size() > MAX_HISTORY) session.history.removeFirst();

                agentLogger.info("Session {} ended for {}", sessionUuid, id);
                agentLogger.info("Updated response id for {} : {} -> {}", id, session.previousId, response.id());
                session.previousId = response.id();

                input.clear();
                var callIds = new HashSet<String>();
                for (var item : response.output()) {
                    var call = item.call();
                    if (call != null && callIds.add(call.id())) {
                        if (Thread.currentThread().isInterrupted() || !allowed.test(id)) {
                            throw new CancellationException("Conversation stopped");
                        }
                        var result = executed.get(call.id());
                        if (result == null) {
                            result = tools.execute(item, snapshot, context, sessionUuid);
                            executed.put(call.id(), result);
                        }
                        input.add(result);
                    }
                }

                if (input.isEmpty()) {
                    store.save(id, session.previousId, session.participants, session.history);
                    rounds = 0;
                    recovered = false;
                    transcript.clear();
                    baseHistory = new ArrayList<>(session.history);
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
    }

    private static final class Session {
        final ArrayDeque<AgentInput.History> history = new ArrayDeque<>();
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
