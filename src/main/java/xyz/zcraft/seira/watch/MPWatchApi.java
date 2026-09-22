package xyz.zcraft.seira.watch;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class MPWatchApi {
    private final String endpoint;
    private final String serviceToken;
    private final HttpClient client;
    private final Gson gson;

    public MPWatchApi(String endpoint) {
        this(endpoint, null);
    }

    public MPWatchApi(String endpoint, String serviceToken) {
        this(endpoint, serviceToken, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(), new Gson());
    }

    MPWatchApi(String endpoint, HttpClient client, Gson gson) {
        this(endpoint, null, client, gson);
    }

    MPWatchApi(String endpoint, String serviceToken, HttpClient client, Gson gson) {
        this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.serviceToken = serviceToken;
        this.client = client;
        this.gson = gson;
    }

    private static JsonElement successfulData(JsonObject root, String action) {
        if (root == null || !root.has("success") || !root.get("success").getAsBoolean()) {
            String message = root != null && root.has("message") && !root.get("message").isJsonNull()
                    ? root.get("message").getAsString()
                    : "未知错误";
            throw new IllegalStateException(action + "失败: " + message);
        }
        JsonElement data = root.get("data");
        if (data == null || data.isJsonNull()) {
            throw new IllegalStateException(action + "失败: 响应缺少 data");
        }
        return data;
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

    private static long requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static MPVersion requireVersion(MPVersion version) {
        if (version == null) {
            throw new IllegalArgumentException("version is required");
        }
        return version;
    }

    public RoomWatchSnapshot getSnapshot(MPVersion version, long roomId) {
        HttpResponse<String> response = get(
                "/multiplayer/rooms/" + requirePositive(roomId, "roomId") + "/watch?version="
                        + requireVersion(version).value(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        if (response.statusCode() == 404) {
            throw new IllegalStateException("未找到指定的多人房间。");
        }
        ensureSuccessfulStatus(response.statusCode(), response.body(), "获取多人房间");
        JsonObject root = gson.fromJson(response.body(), JsonObject.class);
        JsonElement data = successfulData(root, "获取多人房间");
        RoomWatchSnapshot snapshot = gson.fromJson(data, RoomWatchSnapshot.class);
        if (snapshot == null || snapshot.roomId() <= 0) {
            throw new IllegalStateException("获取多人房间失败: 响应缺少房间数据");
        }
        return snapshot;
    }

    public byte[] renderResult(MPVersion version, long roomId, long playlistItemId) {
        HttpResponse<byte[]> response = get(
                "/multiplayer/rooms/" + requirePositive(roomId, "roomId")
                        + "/playlist/" + requirePositive(playlistItemId, "playlistItemId") + "/result?version="
                        + requireVersion(version).value(),
                HttpResponse.BodyHandlers.ofByteArray()
        );
        ensureSuccessfulStatus(response.statusCode(), response.body(), "生成多人房间结果图片");
        if (response.body().length == 0) {
            throw new IllegalStateException("生成多人房间结果图片失败: 响应为空");
        }
        return response.body();
    }

    private <T> HttpResponse<T> get(String path, HttpResponse.BodyHandler<T> handler) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint + path))
                .timeout(Duration.ofMinutes(2))
                .header("Accept",  "image/png");
        if (serviceToken != null && !serviceToken.isBlank()) {
            builder.header("Authorization", "Bearer " + serviceToken);
        }
        HttpRequest request = builder.GET().build();
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
