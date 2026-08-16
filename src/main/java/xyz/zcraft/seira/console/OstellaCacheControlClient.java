package xyz.zcraft.seira.console;

import com.google.gson.Gson;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class OstellaCacheControlClient {
    private static final Gson GSON = new Gson();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final URI endpoint;

    public OstellaCacheControlClient(String endpoint) {
        String normalized = endpoint.replaceAll("/+$", "");
        this.endpoint = URI.create(normalized + "/cache/control");
    }

    public ConsoleRuntimeControl.CacheControlResult control(String operation, String type, long id) {
        String body = GSON.toJson(new Request(operation, type, id));
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(40))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("oStella returned HTTP " + response.statusCode() + ": " + response.body());
            }
            ConsoleRuntimeControl.CacheControlResult result = GSON.fromJson(
                    response.body(), ConsoleRuntimeControl.CacheControlResult.class);
            if (result == null || result.nodes() == null) {
                throw new IllegalStateException("oStella returned an invalid cache control result");
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Cache control request was interrupted", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not reach oStella cache control endpoint", e);
        }
    }

    private record Request(String operation, String type, long id) {
    }
}
