package xyz.zcraft.seira.watch;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OstellaWatchApi implements WatchApi {
    private static final Type SCORE_MAP_TYPE = new TypeToken<Map<String, List<RecentScore>>>() {
    }.getType();

    private final String endpoint;
    private final HttpClient client;
    private final Gson gson;

    public OstellaWatchApi(String endpoint) {
        this(endpoint, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(), new Gson());
    }

    OstellaWatchApi(String endpoint, HttpClient client, Gson gson) {
        this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.client = client;
        this.gson = gson;
    }

    private static void ensureSuccessfulResponse(JsonObject root, String action) {
        if (root == null || !root.has("success") || !root.get("success").getAsBoolean()) {
            String message = root != null && root.has("message") && !root.get("message").isJsonNull()
                    ? root.get("message").getAsString()
                    : "未知错误";
            throw new IllegalStateException(action + "失败: " + message);
        }
    }

    private static void ensureSuccessfulStatus(int statusCode, Object body, String action) {
        if (statusCode >= 200 && statusCode < 300) {
            return;
        }
        String detail = body instanceof byte[] bytes
                ? new String(bytes, StandardCharsets.UTF_8)
                : String.valueOf(body);
        if (detail.length() > 500) {
            detail = detail.substring(0, 500);
        }
        throw new IllegalStateException(action + "失败: HTTP " + statusCode + " " + detail);
    }

    @Override
    public Map<Long, List<RecentScore>> getRecentScores(Collection<Long> userIds, int limit) {
        JsonObject body = new JsonObject();
        body.add("user_ids", gson.toJsonTree(userIds));
        body.addProperty("limit", limit);
        body.addProperty("include_fails", false);

        HttpResponse<String> response = send(
                "/users/scores/recent/batch",
                body.toString(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        ensureSuccessfulStatus(response.statusCode(), response.body(), "批量获取最近成绩");

        JsonObject root = gson.fromJson(response.body(), JsonObject.class);
        ensureSuccessfulResponse(root, "批量获取最近成绩");
        JsonElement data = root.get("data");
        if (data == null || !data.isJsonObject()) {
            throw new IllegalStateException("批量获取最近成绩响应缺少 data");
        }

        Map<String, List<RecentScore>> raw = gson.fromJson(data, SCORE_MAP_TYPE);
        Map<Long, List<RecentScore>> scores = new LinkedHashMap<>();
        raw.forEach((uid, values) -> {
            try {
                scores.put(Long.parseLong(uid), values == null ? List.of() : List.copyOf(values));
            } catch (NumberFormatException e) {
                throw new IllegalStateException("批量成绩响应包含非法用户 ID: " + uid, e);
            }
        });
        return Map.copyOf(scores);
    }

    @Override
    public byte[] renderScore(long userId, long scoreId) {
        JsonObject body = new JsonObject();
        body.addProperty("name", Long.toString(userId));
        body.addProperty("@score", scoreId);

        HttpResponse<byte[]> response = send(
                "/templates/example/render",
                body.toString(),
                HttpResponse.BodyHandlers.ofByteArray()
        );
        ensureSuccessfulStatus(response.statusCode(), response.body(), "生成成绩图片");
        if (response.body().length == 0) {
            throw new IllegalStateException("生成成绩图片响应为空");
        }
        return response.body();
    }

    private <T> HttpResponse<T> send(String path, String body, HttpResponse.BodyHandler<T> handler) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint + path))
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, image/*")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            return client.send(request, handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("oStella 请求被中断", e);
        } catch (IOException e) {
            throw new RuntimeException("无法连接 oStella", e);
        }
    }
}
