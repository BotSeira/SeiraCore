package xyz.zcraft.seira.api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.seira.Seira;
import xyz.zcraft.seira.api.data.RomAIMatch;
import xyz.zcraft.seira.config.DiscordProxyConfig;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedList;
import java.util.List;

public class RomAIApi {
    private static final Gson GSON = new Gson();
    private static final Logger LOG = LogManager.getLogger(RomAIApi.class);

    private static final HttpClient CLIENT;

    static {
        var proxy = Seira.getConfig().discord().proxy();
        CLIENT = HttpClient.newBuilder()
                .proxy(ProxySelector.of(
                        new InetSocketAddress(proxy.host(), proxy.port())
                ))
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    public static List<RomAIMatch> getActiveMatches() {
        try {
            final HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://rom-ai-site.vercel.app/api/active-matches"))
                    .GET()
                    .build();

            final HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            final JsonArray arr = JsonParser.parseString(response.body()).getAsJsonArray();

            List<RomAIMatch> result = new LinkedList<>();

            for (JsonElement jsonElement : arr) {
                result.add(GSON.fromJson(jsonElement, RomAIMatch.class));
            }

            return result;
        } catch (Exception e) {
            LOG.error("Error occurred while fetching active matches", e);
            throw new RuntimeException("Error occurred while fetching active matches", e);
        }
    }

    public static RomAIMatch getMatchFor(String username) {
        final List<RomAIMatch> activeMatches = getActiveMatches();

        return activeMatches.stream()
                .filter(m -> m.players().contains(username))
                .findFirst()
                .orElse(null);
    }
}
