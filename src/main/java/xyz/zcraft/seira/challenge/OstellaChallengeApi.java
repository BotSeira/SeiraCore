package xyz.zcraft.seira.challenge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static xyz.zcraft.seira.challenge.ChallengeModels.*;

public final class OstellaChallengeApi implements ChallengeApi {
    private final String endpoint;
    private final String token;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    private final Gson gson = new Gson();

    public OstellaChallengeApi(String endpoint, String token) {
        this.endpoint = endpoint.replaceAll("/+$", "");
        this.token = token;
    }

    public SetData getBeatmapset(long id) {
        return get("/challenges/beatmapsets/" + id, SetData.class);
    }

    public SetData getBeatmapsetForMap(long id) {
        return get("/challenges/beatmaps/" + id, SetData.class);
    }

    public SkillData getSkill(long uid, long set) {
        return get("/challenges/users/" + uid + "/skill?exclude_set=" + set, SkillData.class);
    }

    public ScoreData getScore(long id) {
        return get("/challenges/scores/" + id, ScoreData.class);
    }

    private <T> T get(String path, Class<T> type) {
        var builder = HttpRequest.newBuilder(URI.create(endpoint + path)).timeout(Duration.ofMinutes(3)).GET();
        if (token != null && !token.isBlank()) builder.header("Authorization", "Bearer " + token);
        try {
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            JsonObject root = gson.fromJson(response.body(), JsonObject.class);
            if ((response.statusCode() == 400 || response.statusCode() == 404) && root != null && root.has("message"))
                throw new IllegalArgumentException(root.get("message").getAsString());
            if (response.statusCode() != 200 || root == null || !root.has("success") || !root.get("success").getAsBoolean())
                throw new IllegalStateException(root != null && root.has("message") ? root.get("message").getAsString()
                        : "挑战数据请求失败（HTTP " + response.statusCode() + "）。");
            T value = gson.fromJson(root.get("data"), type);
            if (value == null) throw new IllegalStateException("挑战数据响应为空。");
            return value;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("挑战数据请求被中断。", e);
        } catch (IOException e) {
            throw new IllegalStateException("无法连接 oStella。", e);
        }
    }
}
