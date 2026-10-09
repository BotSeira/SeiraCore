package xyz.zcraft.seira.whatif;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/** Query-only client. oStella owns samples, interpolation, persistence and refresh. */
public final class WhatIfApi {
    private static final Gson GSON = new Gson();
    private final String endpoint;
    private final String token;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public WhatIfApi(String endpoint, String token) {
        this.endpoint = endpoint.replaceAll("/+$", "");
        this.token = token;
    }

    public Result estimate(WhatIfQuery query) {
        String parameter = query.byPp() ? "pp=" + query.pp() : "rank=" + query.rank();
        var request = HttpRequest.newBuilder(URI.create(endpoint + "/whatif?" + parameter))
                .timeout(Duration.ofSeconds(30)).GET();
        if (token != null && !token.isBlank()) request.header("Authorization", "Bearer " + token);
        try {
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200)
                throw new IllegalStateException("排名估算请求失败（HTTP " + response.statusCode() + "），请确认 oStella 已更新。");
            var root = GSON.fromJson(response.body(), JsonObject.class);
            if (root == null || !root.has("success") || !root.get("success").getAsBoolean())
                throw new IllegalStateException("排名估算请求失败。");
            Result result = GSON.fromJson(root.get("data"), Result.class);
            validate(result, query);
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("排名估算请求被中断。", e);
        } catch (IOException e) {
            throw new IllegalStateException("无法连接 oStella。", e);
        } catch (IllegalArgumentException | com.google.gson.JsonParseException | UnsupportedOperationException e) {
            throw new IllegalStateException("oStella 排名估算返回数据无效。", e);
        }
    }

    private static void validate(Result result, WhatIfQuery query) {
        Set<String> statuses = query.byPp() ? Set.of("COVERED", "HIGH_PP", "LOW_PP")
                : Set.of("COVERED", "LOW_RANK", "HIGH_RANK");
        if (result == null || !"osu".equals(result.mode()) || result.status() == null || !statuses.contains(result.status())
                || !Double.isFinite(result.pp()) || result.pp() <= 0 || !Double.isFinite(result.rank()) || result.rank() < 1
                || result.updatedAt() == null || result.sampleCount() < 2
                || (query.byPp() ? result.pp() != query.pp() : result.rank() != (double) query.rank()))
            throw new IllegalArgumentException("Invalid estimate");
        Instant.parse(result.updatedAt());
    }

    public record Result(String mode, String status, double pp, double rank, String updatedAt,
                         boolean stale, int sampleCount, long minRank, long maxRank, double minPp, double maxPp) {}
}
