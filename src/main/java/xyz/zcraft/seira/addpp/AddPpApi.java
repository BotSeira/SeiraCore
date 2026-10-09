package xyz.zcraft.seira.addpp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

public final class AddPpApi {
    private final String endpoint;
    private final String token;
    private final Gson gson = new Gson();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

    public AddPpApi(String endpoint, String token) {
        this.endpoint = endpoint.replaceAll("/+$", "");
        this.token = token;
    }

    public Result estimate(long userId, AddPpQuery query) {
        var builder = HttpRequest.newBuilder(URI.create(endpoint + "/users/" + userId + "/addpp"))
                .timeout(Duration.ofMinutes(3)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(query)));
        if (token != null && !token.isBlank()) builder.header("Authorization", "Bearer " + token);
        try {
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            JsonObject root = gson.fromJson(response.body(), JsonObject.class);
            if ((response.statusCode() == 400 || response.statusCode() == 404) && root != null && root.has("message"))
                throw new IllegalArgumentException(root.get("message").getAsString());
            if (response.statusCode() != 200 || root == null || !root.has("success") || !root.get("success").getAsBoolean())
                throw new IllegalStateException("PP 估算请求失败（HTTP " + response.statusCode() + "）。");
            AddPpApi.Result result = gson.fromJson(root.get("data"), Result.class);
            if (result == null || result.userId() != userId || !Double.isFinite(result.beforePp()) || !Double.isFinite(result.afterPp()))
                throw new IllegalStateException("PP 估算返回数据不完整。");
            var projection = result.rankProjection();
            if (projection == null || projection.status() == null
                    || !java.util.Set.of("UNCHANGED", "COVERED", "HIGH_PP", "LOW_PP").contains(projection.status())
                    || (!"UNCHANGED".equals(projection.status()) && (projection.rank() == null || projection.rank() < 1)))
                throw new IllegalStateException("排名估算数据缺失，请同步更新 oStella。");
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("PP 估算请求被中断。", e);
        } catch (IOException e) {
            throw new IllegalStateException("无法连接 oStella。", e);
        }
    }

    public record RankProjection(Long rank, String status, String updatedAt, boolean stale) {}

    public record Hits(int great, int ok, int meh, int misses, int combo, double accuracy) {
    }

    public record MapResult(long id, String title, String difficulty, String mods, double stars, int maxCombo,
                            Hits hits) {
    }

    public record Result(String username, long userId, Long rank, double beforePp, double afterPp, double change,
                         double scorePp, int count, int sampled, List<Integer> positions, boolean replaced,
                         MapResult map, RankProjection rankProjection) {
    }
}
