package xyz.zcraft.seira.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import xyz.zcraft.seira.config.AgentConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class ResponsesClient {
    private final HttpClient CLIENT;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final AgentConfig settings;

    public ResponsesClient(AgentConfig settings) {
        this.settings = settings;
        this.endpoint = URI.create(settings.endpoint());
        this.apiKey = settings.apiKey();
        this.model = settings.model();
        this.timeout = Duration.ofSeconds(settings.timeoutSeconds());
        this.CLIENT = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    private static void deliver(JsonObject item, HashSet<String> seen, Consumer<JsonObject> listener) {
        if ("message".equals(item.get("type").getAsString()) && seen.add(item.get("id").getAsString())) {
            listener.accept(item);
        }
    }

    public JsonObject create(
            String previousId, String instructions, JsonArray input, JsonArray tools, Consumer<JsonObject> onMessage
    ) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("instructions", instructions);
        body.addProperty("stream", true);
        body.addProperty("store", true);
        body.add("input", input);
        body.add("tools", tools);
        if (previousId != null) {
            body.addProperty("previous_response_id", previousId);
        }

        if (settings.temperature() != null) {
            body.addProperty("temperature", settings.temperature());
        }
        if (settings.topP() != null) {
            body.addProperty("top_p", settings.topP());
        }
        if (settings.maxOutputTokens() != null) {
            body.addProperty("max_output_tokens", settings.maxOutputTokens());
        }

        if (settings.reasoningEffort() != null) {
            JsonObject reasoning = new JsonObject();
            reasoning.addProperty("effort", settings.reasoningEffort());
            body.add("reasoning", reasoning);
        }
        if (settings.textVerbosity() != null) {
            JsonObject text = new JsonObject();
            text.addProperty("verbosity", settings.textVerbosity());
            body.add("text", text);
        }

        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();

        var pending = CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        HttpResponse<InputStream> response;

        try {
            response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            pending.cancel(true);
            throw e;
        }

        try (var stream = response.body()) {
            if (response.statusCode() / 100 != 2) {
                throw new IOException("Responses HTTP status " + response.statusCode());
            }

            var deadline = CompletableFuture.delayedExecutor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            var finished = new AtomicBoolean();
            deadline.execute(() -> {
                if (!finished.get()) try {
                    stream.close();
                } catch (IOException ignored) {
                }
            });

            try {
                var seen = new HashSet<String>();
                var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
                StringBuilder data = new StringBuilder();
                String line;

                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data:")) {
                        if (!data.isEmpty()) data.append('\n');
                        data.append(line.substring(5).stripLeading());
                        if (data.length() > 4_000_000) throw new IOException("Responses event too large");
                    } else if (line.isEmpty() && !data.isEmpty()) {
                        String payload = data.toString();
                        data.setLength(0);
                        if (payload.equals("[DONE]")) break;

                        JsonObject event = JsonParser.parseString(payload).getAsJsonObject();
                        String type = event.get("type").getAsString();

                        switch (type) {
                            case "response.output_item.done" -> {
                                // deliver(event.getAsJsonObject("item"), seen, onMessage);
                            }
                            case "response.completed" -> {
                                JsonObject result = event.getAsJsonObject("response");
                                for (var item : result.getAsJsonArray("output")) {
                                    deliver(item.getAsJsonObject(), seen, onMessage);
                                }
                                return result;
                            }
                            case "response.failed", "response.incomplete", "error" ->
                                    throw new IOException("Responses request did not complete: " + type);
                        }
                    }
                }

                throw new IOException("Responses stream ended without completion");
            } finally {
                finished.set(true);
            }
        }
    }
}
