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
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class AgentService {
    public static final int CONTEXT_SIZE = 20;
    private final Api api;
    private final Map<StateOwner, State> states = new ConcurrentHashMap<>();

    public AgentService(LLMConfig config) {
        this.api = new Api(config);
    }

    public void recordHistory(String groupId, String sender, String message) {
        if (groupId == null || groupId.isEmpty() || sender == null || sender.isEmpty()) {
            return;
        }

        states.forEach((s, state) -> {
            synchronized (state) {
                if (s.groupId().equals(groupId)) {
                    state.getIncomingMessages().add(sender + ": " + message);

                    while (state.getIncomingMessages().size() > CONTEXT_SIZE) {
                        state.getIncomingMessages().removeFirst();
                    }
                }
            }
        });
    }

    public String input(String groupId, String openId, String input, Consumer<Map<String, String>> var) {
        final StateOwner owner = StateOwner.of(groupId, openId);

        final State state = states.computeIfAbsent(owner, _ -> State.create(api, groupId));

        if (!state.getRunning().compareAndSet(false, true)) {
            throw new IllegalStateException("已有请求正在运行");
        }

        state.resetIfNeeded(api, groupId);

        try {
            if (var != null) {
                final int oldHash = state.getVars().hashCode();

                var.accept(state.getVars());

                if (state.getVars().hashCode() != oldHash) {
                    api.updateConversation(
                            groupId, state.getConv().appConversationID(), state.getVars()
                    );
                }
            }

            final String query = String.join("\n", state.getIncomingMessages()) + "\n\n" + input;

            state.getIncomingMessages().clear();

            final ChatQueryResponse response = api.chatQuery(
                    groupId, state.getConv().appConversationID(), query
            );

            state.getIncomingMessages().add("你: " + response.answer());

            return response.answer();
        } finally {
            state.getRunning().set(false);
        }
    }

    public boolean isRunning(String groupId, String openId) {
        final State state = states.get(StateOwner.of(groupId, openId));
        return state != null && state.getRunning().get();
    }

    public boolean clearState(String groupId, String openId) {
        final State state = states.get(StateOwner.of(groupId, openId));
        if (state != null) {
            state.getResetting().set(true);
            return true;
        }
        return false;
    }

    public int clearStateOfGroup(String groupId) {
        List<StateOwner> toRemove = new ArrayList<>(100);

        states.keySet().forEach((owner) -> {
            if (owner.groupId().equals(groupId)) {
                toRemove.add(owner);
            }
        });

        for (StateOwner stateOwner : toRemove) {
            states.get(stateOwner).getResetting().set(true);
        }

        return toRemove.size();
    }

    public int clearStateOfUser(String openId) {
        List<StateOwner> toRemove = new ArrayList<>(100);

        states.keySet().forEach((owner) -> {
            if (owner.openId().equals(openId)) {
                toRemove.add(owner);
            }
        });

        for (StateOwner stateOwner : toRemove) {
            states.get(stateOwner).getResetting().set(true);
        }

        return toRemove.size();
    }

    public Set<String> activeGroupIds() {
        return states.entrySet().stream()
                .filter(entry -> entry.getValue().getRunning().get())
                .map(entry -> entry.getKey().groupId())
                .collect(Collectors.toSet());
    }

    @Getter
    static final class State {
        private final ConcurrentHashMap<String, String> vars;
        private final AtomicBoolean running;
        private final AtomicBoolean resetting;
        private final List<String> incomingMessages;
        private AppConversationBrief conv;

        State(
                AppConversationBrief conv,
                ConcurrentHashMap<String, String> vars,
                AtomicBoolean running,
                AtomicBoolean resetting,
                List<String> incomingMessages
        ) {
            this.conv = conv;
            this.vars = vars;
            this.running = running;
            this.resetting = resetting;
            this.incomingMessages = incomingMessages;
        }

        public static State create(Api api, String groupId) {
            return new State(
                    api.createConversation(groupId),
                    new ConcurrentHashMap<>(),
                    new AtomicBoolean(false),
                    new AtomicBoolean(false),
                    new LinkedList<>()
            );
        }


        public void resetIfNeeded(Api api, String groupId) {
            if (resetting.compareAndSet(true, false)) {
                conv = api.createConversation(groupId);
                vars.clear();
                incomingMessages.clear();
            }
        }
    }

    record StateOwner(String groupId, String openId) {
        public static StateOwner of(String groupId, String openId) {
            return new StateOwner(groupId, openId);
        }

        public static StateOwner of(Context ctx) {
            return new StateOwner(ctx.groupId(), ctx.senderUserId());
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
