package xyz.zcraft.seira.agent;

import com.google.gson.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

public final class HttpTools {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();

    private HttpTools() {
    }

    public static void reload(Path path, ToolRegistry registry) throws IOException {
        var yaml = new ObjectMapper(new YAMLFactory()).readValue(Files.readString(path), Map.class);
        JsonObject root = new Gson().toJsonTree(yaml).getAsJsonObject();
        List<ToolRegistry.Tool> tools = new ArrayList<>();

        for (var element : root.getAsJsonArray("tools")) {
            JsonObject spec = element.getAsJsonObject();
            if (spec.has("enabled") && !spec.get("enabled").getAsBoolean()) continue;

            tools.add(create(spec));
        }

        registry.replaceConfigured(tools);
    }

    private static final Logger agentLogger = LogManager.getLogger("AgentLogger");

    static ToolRegistry.Tool create(JsonObject spec) {
        String pointer = spec.has("resultPointer") ? spec.get("resultPointer").getAsString() : "";
        if (!pointer.isEmpty() && !pointer.startsWith("/"))
            throw new IllegalArgumentException("Invalid result pointer");

        String url = spec.get("url").getAsString();
        URI base = URI.create(url.replaceAll("\\{[A-Za-z0-9_]+}", "placeholder"));
        if (!Set.of("http", "https").contains(base.getScheme()) || base.getHost() == null
                || base.getUserInfo() != null || base.getFragment() != null)
            throw new IllegalArgumentException("Invalid HTTP tool URL");

        String method = spec.has("method") ? spec.get("method").getAsString().toUpperCase(Locale.ROOT) : "GET";
        if (!Set.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(method))
            throw new IllegalArgumentException("Unsupported tool method");

        Duration timeout = Duration.ofSeconds(spec.has("timeoutSeconds") ? spec.get("timeoutSeconds").getAsLong() : 30);
        Set<String> groups = new HashSet<>();
        if (spec.has("groups")) spec.getAsJsonArray("groups").forEach(g -> groups.add(g.getAsString()));

        Map<String, String> headers = new LinkedHashMap<>();
        if (spec.has("headers")) spec.getAsJsonObject("headers").entrySet().forEach(entry ->
                headers.put(entry.getKey(), environment(entry.getValue().getAsString())));

        JsonObject query = spec.has("query") ? spec.getAsJsonObject("query").deepCopy() : new JsonObject();
        JsonObject paths = spec.has("path") ? spec.getAsJsonObject("path").deepCopy() : new JsonObject();
        JsonElement body = spec.has("body") ? spec.get("body").deepCopy() : null;

        return new ToolRegistry.Tool(spec.get("name").getAsString(), spec.get("description").getAsString(),
                spec.getAsJsonObject("parameters"), groups, timeout, (arguments, context) -> {
            String resolvedUrl = url;
            for (var entry : paths.entrySet()) {
                JsonElement value = resolve(entry.getValue(), arguments);
                if (value.isJsonNull()) throw new IllegalArgumentException("Missing path argument");
                String segment = value.getAsString();
                if (segment.equals(".") || segment.equals(".."))
                    throw new IllegalArgumentException("Invalid path segment");
                resolvedUrl = resolvedUrl.replace("{" + entry.getKey() + "}", encode(segment).replace("+", "%20"));
            }
            URI targetUri = URI.create(resolvedUrl);
            if (!java.util.Objects.equals(base.getRawAuthority(), targetUri.getRawAuthority())
                    || !base.getScheme().equals(targetUri.getScheme()))
                throw new IllegalArgumentException("Tool origin changed");
            StringBuilder target = new StringBuilder(resolvedUrl);
            for (var entry : query.entrySet()) {
                JsonElement value = resolve(entry.getValue(), arguments);
                if (value.isJsonNull()) continue;
                target.append(target.indexOf("?") < 0 ? '?' : '&').append(encode(entry.getKey()))
                        .append('=').append(encode(value.isJsonPrimitive() ? value.getAsString() : value.toString()));
            }

            var request = HttpRequest.newBuilder(URI.create(target.toString())).timeout(timeout);
            headers.forEach(request::header);
            if (body != null) request.header("Content-Type", "application/json");
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(resolve(body, arguments).toString()));

            var response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());

            agentLogger.debug("Http tool returned status {} body {}",
                    response.statusCode(), response.body().substring(0, Math.min(response.body().length(), 250))
            );

            if (response.statusCode() / 100 != 2) {
                throw new IOException("HTTP tool failed");
            }

            String text = response.body();
            JsonElement result;
            try {
                result = JsonParser.parseString(text);
            } catch (JsonParseException e) {
                result = new JsonPrimitive(text);
            }

            if (!pointer.isEmpty()) for (String segment : pointer.substring(1).split("/", -1)) {
                String key = segment.replace("~1", "/").replace("~0", "~");
                result = result.isJsonArray() ? result.getAsJsonArray().get(Integer.parseInt(key))
                        : result.getAsJsonObject().get(key);
                if (result == null) throw new IOException("HTTP tool result pointer not found");
            }

            return result;
        }, spec.has("adminOnly") && spec.get("adminOnly").getAsBoolean(),
                spec.has("requiresConfirmation") ? spec.get("requiresConfirmation").getAsBoolean() : !method.equals("GET"));
    }

    private static String environment(String value) {
        if (!value.startsWith("env:")) return value;
        String result = System.getenv(value.substring(4));
        if (result == null) throw new IllegalArgumentException("Missing HTTP tool environment variable");

        return result;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * An exact $argument reference preserves JSON types; ordinary strings remain literal.
     */
    private static JsonElement resolve(JsonElement template, JsonObject arguments) {
        if (template.isJsonPrimitive() && template.getAsJsonPrimitive().isString()) {
            String value = template.getAsString();
            if (value.startsWith("$")) return arguments.has(value.substring(1))
                    ? arguments.get(value.substring(1)).deepCopy() : JsonNull.INSTANCE;
        }

        if (template.isJsonObject()) {
            JsonObject object = new JsonObject();
            template.getAsJsonObject().entrySet().forEach(e -> {
                JsonElement value = resolve(e.getValue(), arguments);
                if (!value.isJsonNull()) object.add(e.getKey(), value);
            });
            return object;
        }

        if (template.isJsonArray()) {
            JsonArray array = new JsonArray();
            template.getAsJsonArray().forEach(e -> array.add(resolve(e, arguments)));
            return array;
        }

        return template.deepCopy();
    }
}
