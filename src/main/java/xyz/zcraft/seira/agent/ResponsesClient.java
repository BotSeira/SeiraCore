package xyz.zcraft.seira.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.seira.config.AgentConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class ResponsesClient {
    private static final Logger agentLogger = LogManager.getLogger("AgentLogger");
    private final HttpClient CLIENT;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final AgentConfig config;

    public ResponsesClient(AgentConfig config) {
        this.config = config;
        this.endpoint = URI.create(config.endpoint());
        this.apiKey = config.apiKey();
        this.model = config.model();
        this.timeout = Duration.ofSeconds(config.timeoutSeconds());
        final ProxySelector proxySelector = config.proxy().enabled()
                ? ProxySelector.of(new InetSocketAddress(config.proxy().host(), config.proxy().port()))
                : ProxySelector.getDefault();
        this.CLIENT = HttpClient.newBuilder().connectTimeout(timeout).proxy(proxySelector).build();
    }

    public JsonObject create(
            String previousId, String instructions, JsonArray input, JsonArray tools, Consumer<String> onMessage
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

        if (config.settings().temperature() != null) {
            body.addProperty("temperature", config.settings().temperature());
        }
        if (config.settings().topP() != null) {
            body.addProperty("top_p", config.settings().topP());
        }
        if (config.settings().maxOutputTokens() != null) {
            body.addProperty("max_output_tokens", config.settings().maxOutputTokens());
        }

        if (config.settings().reasoningEffort() != null) {
            JsonObject reasoning = new JsonObject();
            reasoning.addProperty("effort", config.settings().reasoningEffort());
            body.add("reasoning", reasoning);
        }
        if (config.settings().textVerbosity() != null) {
            JsonObject text = new JsonObject();
            text.addProperty("verbosity", config.settings().textVerbosity());
            body.add("text", text);
        }

        body.add("context_management", JsonParser.parseString("""
                [
                  {
                    "type": "compaction",
                    "compact_threshold": 800000
                  }
                ]
                """));

        final JsonObject cpy = body.deepCopy();
        if (cpy.has("instructions") && cpy.get("instructions").isJsonPrimitive()) {
            cpy.remove("instructions");
            cpy.addProperty("instructions", "...");
        }
        agentLogger.debug("Sending request: {}", cpy);

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
                String line = "";
                try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    line = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
                } catch (IOException _) {
                }
                agentLogger.error("Responses HTTP status {}, {}", response.statusCode(), line);
                JsonObject details;
                try {
                    details = JsonParser.parseString(line).getAsJsonObject();
                } catch (RuntimeException ignored) {
                    details = new JsonObject();
                }
                throw new ResponseException("Responses HTTP status " + response.statusCode() + ", " + line, details);
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
                var messages = new MessageBuffer(onMessage);
                var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
                StringBuilder data = new StringBuilder();
                String line;

                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data:")) {
                        if (!data.isEmpty()) data.append('\n');
                        data.append(line.substring(5).stripLeading());
//                        if (data.length() > 4_000_000) throw new IOException("Responses event too large");
                    } else if (line.isEmpty() && !data.isEmpty()) {
                        String payload = data.toString();
                        data.setLength(0);
                        if (payload.equals("[DONE]")) break;

                        JsonObject event = JsonParser.parseString(payload).getAsJsonObject();
                        String type = event.get("type").getAsString();

                        final JsonObject cpyEvent = event.deepCopy();
                        if (cpyEvent.has("response") && cpyEvent.get("response").isJsonObject()) {
                            final JsonObject obj1 = cpy.get("response").getAsJsonObject();
                            if (obj1.has("instructions") && obj1.get("instructions").isJsonPrimitive()) {
                                obj1.remove("instructions");
                                obj1.addProperty("instructions", "...");
                            }
                        }
                        agentLogger.debug("Receiving event {}", cpyEvent);

                        switch (type) {
                            case "response.output_text.delta", "response.refusal.delta" ->
                                    messages.append(event.get("delta").getAsString());
                            case "response.completed" -> {
                                messages.finish();
                                return event.getAsJsonObject("response");
                            }
                            case "response.failed", "response.incomplete", "error" ->
                                    throw new ResponseException("Responses request did not complete: " + event, event);
                        }
                    }
                }

                throw new IOException("Responses stream ended without completion");
            } finally {
                finished.set(true);
            }
        }
    }

    static final class ResponseException extends IOException {
        private final String code;

        ResponseException(String message, JsonObject event) {
            super(message);
            JsonObject details = event;
            if (details.has("response") && details.get("response").isJsonObject())
                details = details.getAsJsonObject("response");
            if (details.has("error") && details.get("error").isJsonObject())
                details = details.getAsJsonObject("error");
            code = details.has("code") && details.get("code").isJsonPrimitive()
                    ? details.get("code").getAsString() : null;
        }

        boolean previousResponseMissing() {
            return "previous_response_not_found".equals(code);
        }
    }

    static final class MessageBuffer {
        private static final String SEPARATOR = "\n\n---\n\n";
        private final StringBuilder pending = new StringBuilder();
        private final Consumer<String> send;

        MessageBuffer(Consumer<String> send) {
            this.send = send;
        }

        void append(String delta) {
            pending.append(delta);
            int boundary;
            while ((boundary = pending.indexOf(SEPARATOR)) >= 0) {
                String message = pending.substring(0, boundary);
                if (!message.isBlank()) send.accept(message);
                pending.delete(0, boundary + SEPARATOR.length());
            }
        }

        void finish() {
            if (!pending.toString().isBlank()) send.accept(pending.toString());
            pending.setLength(0);
        }
    }
}
