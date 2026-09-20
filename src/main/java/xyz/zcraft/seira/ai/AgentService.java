package xyz.zcraft.seira.ai;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.seira.ai.data.AppConversationBrief;
import xyz.zcraft.seira.ai.data.ChatQueryResponse;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.config.LLMConfig;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class AgentService {
    private final Api api;
    private final Map<StateOwner, State> states = new ConcurrentHashMap<>();

    public AgentService(LLMConfig config) {
        this.api = new Api(config);
    }

    public String input(String groupId, String openId, String input, Consumer<Map<String, String>> var) {
        final StateOwner owner = StateOwner.of(groupId, openId);

        final State state = states.computeIfAbsent(
                owner,
                _ -> new State(
                        api.createConversation(groupId),
                        new ConcurrentHashMap<>(),
                        new AtomicBoolean(false)
                )
        );

        if (!state.running().compareAndSet(false, true)) {
            throw new IllegalStateException("已有请求正在运行");
        }

        try {
            if (var != null) {
                final int oldHash = state.vars().hashCode();

                var.accept(state.vars());

                if (state.vars().hashCode() != oldHash) {
                    api.updateConversation(
                            groupId, state.conv().appConversationID(), state.vars()
                    );
                }
            }

            final ChatQueryResponse response = api.chatQuery(
                    groupId, state.conv().appConversationID(), input
            );

            return response.answer();
        } finally {
            state.running().set(false);
        }
    }

    public boolean isRunning(String groupId, String openId) {
        final State state = states.get(StateOwner.of(groupId, openId));
        return state != null && state.running().get();
    }

    public boolean clearState(String groupId, String openId) {
        return states.remove(StateOwner.of(groupId, openId)) != null;
    }

    public int clearStateOfGroup(String groupId) {
        List<StateOwner> toRemove = new ArrayList<>(100);

        states.keySet().forEach((owner) -> {
            if (owner.groupId().equals(groupId)) {
                toRemove.add(owner);
            }
        });

        for (StateOwner stateOwner : toRemove) {
            states.remove(stateOwner);
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
            states.remove(stateOwner);
        }

        return toRemove.size();
    }

    record State(
            AppConversationBrief conv, ConcurrentHashMap<String, String> vars,
            AtomicBoolean running
    ) {
    }

    record StateOwner(String groupId, String openId) {
        public static StateOwner of(String groupId, String openId) {
            return new StateOwner(groupId, openId);
        }

        public static StateOwner of(Context ctx) {
            return new StateOwner(ctx.groupId(), ctx.senderUserId());
        }
    }

    public Set<String> activeGroupIds() {
        return states.entrySet().stream()
                .filter(entry -> entry.getValue().running().get())
                .map(entry -> entry.getKey().groupId())
                .collect(Collectors.toSet());
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
