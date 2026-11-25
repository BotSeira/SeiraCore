package xyz.zcraft.seira.agent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.seira.config.AsteroidConfig;
import xyz.zcraft.seira.config.OstellaConfig;
import xyz.zcraft.seira.db.UserDataStore;

import java.net.URI;
import java.util.List;
import java.util.function.Function;

final class ServiceTools {
    private static final Logger agentLogger = LogManager.getLogger("AgentLogger");
    private static final List<String> VERBOSITY_LEVELS = List.of("compact", "standard", "full");
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

    void declare(String service, String route, String name, String description, String method,
                 ToolSchema schema, String[] pathParameters, String[] queryParameters, boolean jsonBody,
                 boolean adminOnly, boolean requiresConfirmation) {
        declare(service, route, name, description, method, schema, pathParameters, queryParameters,
                jsonBody, adminOnly, requiresConfirmation, null);
    }

    void declareOstellaWithVerbosity(String route, String name, String description, ToolSchema schema,
                                     String[] pathParameters, String[] queryParameters, String defaultVerbosity) {
        String verbosity = ostella.toolVerbosity().getOrDefault(name, defaultVerbosity);
        if (!VERBOSITY_LEVELS.contains(verbosity)) {
            throw new IllegalArgumentException("Invalid ostella.toolVerbosity for " + name + ": " + verbosity);
        }
        declare("ostella", route, name, description, "GET", schema, pathParameters, queryParameters,
                false, false, false, verbosity);
    }

    private void declare(String service, String route, String name, String description, String method,
                         ToolSchema schema, String[] pathParameters, String[] queryParameters, boolean jsonBody,
                         boolean adminOnly, boolean requiresConfirmation, String defaultVerbosity) {
        JsonObject spec = new JsonObject();
        spec.addProperty("name", name);
        spec.addProperty("description", description);
        spec.addProperty("method", method);
        JsonObject query = argumentMapping(queryParameters);
        if (defaultVerbosity != null) {
            schema.property("verbosity", ToolSchema.string().enumValues(VERBOSITY_LEVELS.toArray())
                    .description("谱面数据详情级别：compact 基础信息，standard 含难度属性，full 完整信息。默认 "
                            + defaultVerbosity + "。"));
            query.addProperty("verbosity", "$verbosity");
        }
        spec.add("parameters", schema.toJson());
        spec.addProperty("timeoutSeconds", 60);
        spec.addProperty("adminOnly", adminOnly);
        spec.addProperty("requiresConfirmation", requiresConfirmation);
        spec.add("path", argumentMapping(pathParameters));
        spec.add("query", query);
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

        registry.register(new ToolRegistry.Tool(http.name(), http.description(), http.parameters(),
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

            JsonObject requestArguments = arguments;
            if (defaultVerbosity != null && !arguments.has("verbosity")) {
                requestArguments = arguments.deepCopy();
                requestArguments.addProperty("verbosity", defaultVerbosity);
            }
            JsonElement result = request.handler().execute(requestArguments, context);
            if (browser) browsers.record(route, arguments, context.conversationId(), result);
            return result;
        }, http.adminOnly(), http.requiresConfirmation()));
    }
}
