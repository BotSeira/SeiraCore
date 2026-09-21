package xyz.zcraft.seira.ai;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.Getter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.seira.ai.data.AppConversationBrief;
import xyz.zcraft.seira.ai.data.ChatQueryResponse;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.config.LLMConfig;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

public class AgentService {
    public static final int CONTEXT_SIZE = 20;

    private final Api api;

    private final Map<StateOwner, State> states = new ConcurrentHashMap<>();

    private final Map<String, Deque<String>> chatLog = new ConcurrentHashMap<>();

    private final Map<StateOwner, Object> stateCreationLocks = new ConcurrentHashMap<>();

    public AgentService(LLMConfig config) {
        this.api = new Api(config);
    }

    private static void restoreIncomingMessages(
            State state,
            Collection<String> pendingMessages
    ) {
        if (pendingMessages.isEmpty()) {
            return;
        }

        synchronized (state) {
            final Deque<String> merged = new ArrayDeque<>(
                    pendingMessages.size()
                            + state.incomingMessages.size()
            );

            merged.addAll(pendingMessages);
            merged.addAll(state.incomingMessages);

            state.incomingMessages.clear();
            state.incomingMessages.addAll(merged);

            trimToContextSize(state.incomingMessages);
        }
    }

    private static void trimToContextSize(Deque<String> messages) {
        while (messages.size() > CONTEXT_SIZE) {
            messages.removeFirst();
        }
    }

    public void recordHistory(String groupId, String sender, String message) {
        if (groupId == null || groupId.isEmpty()
                || sender == null || sender.isEmpty()
                || message == null || message.isEmpty()) {
            return;
        }

        final String historyMessage = sender + ": " + message;

        final Deque<String> log = chatLog.computeIfAbsent(groupId, _ -> new ArrayDeque<>());

        synchronized (log) {
            log.addLast(historyMessage);
            trimToContextSize(log);

            states.forEach((owner, state) -> {
                if (!owner.groupId().equals(groupId)) {
                    return;
                }

                synchronized (state) {
                    state.incomingMessages.addLast(historyMessage);
                    trimToContextSize(state.incomingMessages);
                }
            });
        }
    }

    public String input(String groupId, String openId, String rawContent, Function<String, String> contextFunc) {
        final StateOwner owner = StateOwner.of(groupId, openId);
        final State state = getOrCreateState(owner);

        if (!state.running.compareAndSet(false, true)) {
            throw new IllegalStateException("已有请求正在运行");
        }

        try {
            recordHistory(groupId, openId, rawContent);

            final Deque<String> pendingMessages;

            synchronized (state) {
                pendingMessages = new ArrayDeque<>(state.incomingMessages);
                state.incomingMessages.clear();
            }

            final String query;

            if (pendingMessages.isEmpty()) {
                query = openId + ": " + rawContent;
            } else {
                query = String.join("\n", pendingMessages)
                        + "\n"
                        + "\n"
                        + openId + ": " + rawContent;
            }

            state.resetIfNeeded(api, groupId);

            if (contextFunc != null) {
                state.getVars().put("CONTEXT", contextFunc.apply(query));
            }

            api.updateConversation(groupId, state.conv.appConversationID(), state.vars);


            final ChatQueryResponse response;

            try {
                response = api.chatQuery(groupId, state.conv.appConversationID(), query);
            } catch (RuntimeException | Error e) {
                restoreIncomingMessages(state, pendingMessages);

                throw e;
            }

            recordHistory(groupId, "Seira(你,回复" + openId + "的消息)", response.answer());

            return response.answer();
        } finally {
            state.running.set(false);
        }
    }

    public boolean isRunning(String groupId, String openId) {
        final State state = states.get(
                StateOwner.of(groupId, openId)
        );

        return state != null && state.running.get();
    }

    public boolean clearState(String groupId, String openId) {
        final State state = states.get(
                StateOwner.of(groupId, openId)
        );

        if (state == null) {
            return false;
        }

        state.resetting.set(true);
        return true;
    }

    public int clearStateOfGroup(String groupId) {
        int count = 0;

        for (Map.Entry<StateOwner, State> entry : states.entrySet()) {
            if (!entry.getKey().groupId().equals(groupId)) {
                continue;
            }

            entry.getValue().resetting.set(true);
            count++;
        }

        return count;
    }

    public int clearStateOfUser(String openId) {
        int count = 0;

        for (Map.Entry<StateOwner, State> entry : states.entrySet()) {
            if (!entry.getKey().openId().equals(openId)) {
                continue;
            }

            entry.getValue().resetting.set(true);
            count++;
        }

        return count;
    }

    public Set<String> activeGroupIds() {
        return states.entrySet()
                .stream()
                .filter(entry -> entry.getValue().running.get())
                .map(entry -> entry.getKey().groupId())
                .collect(Collectors.toSet());
    }

    private State getOrCreateState(StateOwner owner) {
        State state = states.get(owner);

        if (state != null) {
            return state;
        }

        final Object creationLock = stateCreationLocks.computeIfAbsent(owner, _ -> new Object());

        try {
            synchronized (creationLock) {
                state = states.get(owner);

                if (state != null) {
                    return state;
                }

                final String groupId = owner.groupId();
                final Deque<String> log = chatLog.computeIfAbsent(groupId, _ -> new ArrayDeque<>());
                final AppConversationBrief conv = api.createConversation(groupId);

                synchronized (log) {
                    final State created = State.create(conv, new ArrayList<>(log));

                    states.put(owner, created);

                    return created;
                }
            }
        } finally {
            stateCreationLocks.remove(owner, creationLock);
        }
    }

    @Getter
    static final class State {
        private final ConcurrentHashMap<String, String> vars;
        private final AtomicBoolean running;
        private final AtomicBoolean resetting;
        private final Deque<String> incomingMessages;
        private AppConversationBrief conv;

        private State(
                AppConversationBrief conv,
                ConcurrentHashMap<String, String> vars,
                AtomicBoolean running,
                AtomicBoolean resetting,
                Deque<String> incomingMessages
        ) {
            this.conv = conv;
            this.vars = vars;
            this.running = running;
            this.resetting = resetting;
            this.incomingMessages = incomingMessages;
        }


        public static State create(
                AppConversationBrief conv,
                Collection<String> incomingMessages
        ) {
            return new State(
                    conv,
                    new ConcurrentHashMap<>(),
                    new AtomicBoolean(false),
                    new AtomicBoolean(false),
                    new ArrayDeque<>(incomingMessages)
            );
        }

        public void resetIfNeeded(Api api, String groupId) {
            if (!resetting.compareAndSet(true, false)) {
                return;
            }

            try {
                conv = api.createConversation(groupId);
                vars.clear();
            } catch (RuntimeException | Error e) {
                resetting.set(true);
                throw e;
            }
        }
    }


    record StateOwner(String groupId, String openId) {
        public static StateOwner of(
                String groupId,
                String openId
        ) {
            return new StateOwner(groupId, openId);
        }

        public static StateOwner of(Context ctx) {
            return new StateOwner(
                    ctx.groupId(),
                    ctx.senderUserId()
            );
        }
    }
}

class Api {
    private static final Logger LOG = LogManager.getLogger(Api.class);
    public final HttpClient CLIENT = HttpClient.newHttpClient();
    public final Gson GSON = new Gson();
    public final String apiKey;
    public final String endpoint;

    public Api(LLMConfig config) {
        this.endpoint = config.baseUrl();
        this.apiKey = config.apiKey();
    }

    public AppConversationBrief createConversation(String openId) {
        LOG.info("Creating conversation for user {}", openId);
        JsonObject body = new JsonObject();
        body.addProperty("UserID", openId);
        try {
            var request = newRequest("/api/proxy/api/v1/create_conversation")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            final HttpResponse<String> send = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (send.statusCode() != 200) {
                throw new RuntimeException("Failed to create conversation: " + send.statusCode());
            }

            final var response = JsonParser.parseString(send.body()).getAsJsonObject();

            final AppConversationBrief conversation = GSON.fromJson(
                    response.getAsJsonObject("Conversation"),
                    AppConversationBrief.class
            );

            LOG.info("Conversation for user {} created, id {}", openId, conversation.appConversationID());
            return conversation;
        } catch (Exception e) {
            throw new RuntimeException("Error creating conversation", e);
        }
    }

    public void updateConversation(String openId, String appConvId, Map<String, String> variables) {
        LOG.info("Updating conversation for user {}", openId);

        JsonObject body = new JsonObject();
        body.addProperty("UserID", openId);
        body.addProperty("AppConversationID", appConvId);
        body.add("Inputs", GSON.toJsonTree(variables));

        try {
            var request = newRequest("/api/proxy/api/v1/update_conversation")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            final HttpResponse<String> send = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (send.statusCode() != 200) {
                throw new RuntimeException("Failed to update conversation: " + send.statusCode());
            }

            LOG.info("Updated conversation for user {}", openId);
        } catch (Exception e) {
            throw new RuntimeException("Error updating conversation", e);
        }
    }

    public ChatQueryResponse chatQuery(String openId, String appConvId, String query) {
        LOG.info("Running chat query for user {}", openId);

        JsonObject body = new JsonObject();
        body.addProperty("UserID", openId);
        body.addProperty("AppConversationID", appConvId);
        body.addProperty("Query", query);
        body.addProperty("ResponseMode", "blocking");

        try {
            var request = newRequest("/api/proxy/api/v1/chat_query_v2")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            final HttpResponse<String> send = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (send.statusCode() != 200) {
                throw new RuntimeException("Failed to query conversation: " + send.statusCode());
            }

            final ChatQueryResponse chatQueryResponse = GSON.fromJson(send.body(), ChatQueryResponse.class);

            LOG.info(
                    "Chat query success for group {}, Token input:{}, output:{}",
                    openId,
                    chatQueryResponse.inputTokens(),
                    chatQueryResponse.outputTokens()
            );

            return chatQueryResponse;
        } catch (Exception e) {
            throw new RuntimeException("Error querying conversation", e);
        }
    }

    private HttpRequest.Builder newRequest(String path) {
        return HttpRequest.newBuilder()
                .uri(java.net.URI.create(this.endpoint + path))
                .header("Apikey", this.apiKey)
                .header("Content-Type", "application/json");
    }
}
