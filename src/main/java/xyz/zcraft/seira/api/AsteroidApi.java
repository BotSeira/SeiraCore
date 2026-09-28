package xyz.zcraft.seira.api;

import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.seira.Seira;
import xyz.zcraft.seira.api.data.MinecraftServerStatus;
import xyz.zcraft.seira.api.data.RawResponse;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class AsteroidApi {
    private static final String ENDPOINT;
    private static final String TOKEN;
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofMinutes(5)).build();
    private static final Gson GSON = new Gson();

    static {
        ENDPOINT = Seira.getConfig().asteroid().endpoint();
        TOKEN = Seira.getConfig().asteroid().token();
    }

    private static HttpRequest.Builder requestBuilder(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder();
        if (TOKEN != null && !TOKEN.isBlank()) {
            builder.header("Authorization", "Bearer " + TOKEN);
        }
        builder.uri(URI.create(ENDPOINT + path));
        return builder;
    }

    public static MinecraftServerStatus getMinecraftServerStatus(String addr) {
        try {
            var request = requestBuilder("/minecraft/servers/" + URLEncoder.encode(addr, StandardCharsets.UTF_8) + "/status")
                    .GET()
                    .build();

            final HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw ApiUtil.parseHttpError(response.body(), response.statusCode(), "获取 MC 服务器状态失败");
            }

            final RawResponse r = GSON.fromJson(response.body(), RawResponse.class);
            ApiUtil.ensureApiSuccess(r, "获取 MC 服务器状态失败");

            return GSON.fromJson(r.getData(), MinecraftServerStatus.class);
        } catch (Exception e) {
            throw new RuntimeException("获取 MC 服务器状态失败", e);
        }
    }

    public static boolean getServerStatus() {
        try {
            var request = requestBuilder("/health")
                    .GET()
                    .build();

            final HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return true;
            }
        } catch (Exception e) {
            LOG.error("Failed to get server status", e);
        }

        LOG.warn("Asteroid server is down.");
        return false;
    }

    private static final Logger LOG = LogManager.getLogger(AsteroidApi.class);
}
