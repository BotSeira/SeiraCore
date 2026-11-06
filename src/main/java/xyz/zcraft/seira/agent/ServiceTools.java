package xyz.zcraft.seira.agent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.seira.config.AsteroidConfig;
import xyz.zcraft.seira.config.OstellaConfig;
import xyz.zcraft.seira.db.UserDataStore;

import java.net.URI;
import java.util.function.Function;

final class ServiceTools {
    private final ToolRegistry registry;
    private final OstellaConfig ostella;
    private final AsteroidConfig asteroid;
    private final Function<String, String> osuToken;
    private final BrowserSessions browsers = new BrowserSessions();

    ServiceTools(ToolRegistry registry, OstellaConfig ostella, AsteroidConfig asteroid, Function<String, String> osuToken) {
        this.registry = registry;
        this.ostella = ostella;
        this.asteroid = asteroid;
        this.osuToken = osuToken;
    }

    private static JsonObject argumentMapping(String[] names) {
        JsonObject mapping = new JsonObject();
        for (String name : names) mapping.addProperty(name, "$" + name);
        return mapping;
    }

    private static final Logger agentLogger = LogManager.getLogger("AgentLogger");

    void declare(String service, String category, String route, String name, String description, String method,
                 ToolSchema schema, String[] pathParameters, String[] queryParameters, boolean jsonBody,
                 boolean adminOnly, boolean requiresConfirmation) {
        JsonObject spec = new JsonObject();
        spec.addProperty("name", name);
        spec.addProperty("description", description);
        spec.addProperty("method", method);
        spec.add("parameters", schema.toJson());
        spec.addProperty("timeoutSeconds", 60);
        spec.addProperty("adminOnly", adminOnly);
        spec.addProperty("requiresConfirmation", requiresConfirmation);
        spec.add("path", argumentMapping(pathParameters));
        spec.add("query", argumentMapping(queryParameters));
        if (jsonBody) spec.addProperty("body", "$body");

        JsonObject headers = new JsonObject();
        headers.addProperty("Accept", "application/json");
        headers.addProperty("X-Error-Mode", "body");
        spec.add("headers", headers);
        String endpoint = service.equals("ostella") ? ostella.endpoint() : asteroid.endpoint();
        String secret = service.equals("ostella") ? ostella.token() : asteroid.token();
        spec.addProperty("url", endpoint.replaceAll("/+$", "") + route);
        if (secret != null && !secret.isBlank())
            spec.getAsJsonObject("headers").addProperty("Authorization", "Bearer " + secret);
        ToolRegistry.Tool http = HttpTools.create(spec);
        boolean browser = service.equals("asteroid") && route.startsWith("/browsers/");
        boolean oauth = service.equals("ostella") && (route.equals("/beatmaps/lookup") || route.equals("/beatmapsets/lookup"));

        registry.registerDeferred(category, new ToolRegistry.Tool(http.name(), http.description(), http.parameters(),
                http.groups(), http.timeout(), (arguments, context) -> {
            agentLogger.debug("Executing tool {} with args {}", http.name(), arguments);
            if (browser) {
                browsers.check(route, arguments, context.conversationId());
                if (route.endsWith("/navigate")) {
                    URI uri = URI.create(arguments.getAsJsonObject("body").get("url").getAsString());
                    if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null)
                        throw new IllegalArgumentException("Invalid navigation URL");
                }
            }

            ToolRegistry.Tool request = http;
            if (oauth && arguments.has("of") && "mp".equals(arguments.get("of").getAsString())) {
                if (context.participants().size() != 1)
                    throw new IllegalArgumentException("One requester required for OAuth");
                String user = context.participants().iterator().next();
                if (!UserDataStore.isGroupMember(context.conversationId(), user))
                    throw new IllegalArgumentException("Not a group member");
                String token = osuToken.apply(user);
                if (token == null || token.isBlank()) throw new IllegalArgumentException("Missing osu authorization");
                JsonObject authorized = spec.deepCopy();
                authorized.getAsJsonObject("headers").addProperty("X-Osu-Authorization", "Bearer " + token);
                request = HttpTools.create(authorized);
            }

            JsonElement result = request.handler().execute(arguments, context);
            if (browser) browsers.record(route, arguments, context.conversationId(), result);
            return result;
        }, http.adminOnly(), http.requiresConfirmation()));
    }
}
