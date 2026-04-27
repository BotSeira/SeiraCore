package xyz.zcraft.platform.napcat;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.data.FileInfo;
import xyz.zcraft.data.Message;
import xyz.zcraft.data.PendingMessage;
import xyz.zcraft.platform.PlatformPrivateMessageApi;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class NapcatPrivateMessageApi implements PlatformPrivateMessageApi {
    private static final Logger LOG = LogManager.getLogger(NapcatPrivateMessageApi.class);
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private static final Gson GSON = new Gson();

    private final String httpEndpoint;
    private final String authToken;

    public NapcatPrivateMessageApi(String httpEndpoint, String authToken) {
        this.httpEndpoint = normalizeEndpoint(httpEndpoint);
        this.authToken = authToken == null ? "" : authToken.trim();
    }

    @Override
    public void sendPrivateMessage(String userId, Message message) {
        JsonObject payload = new JsonObject();
        addUserId(payload, userId);
        payload.addProperty("message", buildMessageText(message));
        post("/send_private_msg", payload);
    }

    @Override
    public void sendGroupMessage(String groupId, Message message) {
        JsonObject payload = new JsonObject();
        addGroupId(payload, groupId);
        payload.addProperty("message", buildMessageText(message));
        post("/send_group_msg", payload);
    }

    @Override
    public FileInfo uploadPrivateMedia(String userId, int fileType, String url) {
        return buildCqMedia(fileType, url, false);
    }

    @Override
    public FileInfo uploadPrivateMediaBase64(String userId, int fileType, String base64Str) {
        return buildCqMedia(fileType, base64Str, true);
    }

    @Override
    public FileInfo uploadGroupMedia(String groupId, int fileType, String url) {
        return buildCqMedia(fileType, url, false);
    }

    @Override
    public FileInfo uploadGroupMediaBase64(String groupId, int fileType, String base64Str) {
        return buildCqMedia(fileType, base64Str, true);
    }

    private FileInfo buildCqMedia(int fileType, String source, boolean base64) {
        String cqType = switch (fileType) {
            case PendingMessage.FILE_TYPE_VIDEO -> "video";
            case PendingMessage.FILE_TYPE_IMAGE -> "image";
            default -> {
                LOG.warn("Unsupported fileType {} for napcat media, fallback to image", fileType);
                yield "image";
            }
        };
        String fileValue = base64 ? "base64://" + source : source;

        FileInfo fileInfo = new FileInfo();
        fileInfo.setString("[CQ:" + cqType + ",file=" + fileValue + "]");
        return fileInfo;
    }

    private String buildMessageText(Message message) {
        StringBuilder builder = new StringBuilder();
        if (message.getContent() != null) {
            builder.append(message.getContent());
        }
        if (message.getMedia() != null && message.getMedia().getString() != null) {
            if (!builder.isEmpty()) {
                builder.append("\n");
            }
            builder.append(message.getMedia().getString());
        }
        return builder.toString();
    }

    private void addUserId(JsonObject payload, String userId) {
        try {
            payload.addProperty("user_id", Long.parseLong(userId));
        } catch (NumberFormatException e) {
            payload.addProperty("user_id", userId);
        }
    }

    private void addGroupId(JsonObject payload, String groupId) {
        try {
            payload.addProperty("group_id", Long.parseLong(groupId));
        } catch (NumberFormatException e) {
            payload.addProperty("group_id", groupId);
        }
    }

    private void post(String path, JsonObject payload) {
        String requestUrl = buildRequestUrl(path);
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(requestUrl))
                    .version(HttpClient.Version.HTTP_1_1)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)));
            if (!authToken.isEmpty()) {
                builder.header("Authorization", "Bearer " + authToken);
            }

            HttpResponse<String> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String message = "Napcat API request failed: method=POST url=" + requestUrl + " status=" + response.statusCode() + " protocol=" + response.version() + " body=" + response.body();
                if (response.statusCode() == 405) {
                    message += " ; tip: endpoint/proxy may only accept HTTP/1.1 API routes. Confirm SEIRA_NAPCAT_HTTP_ENDPOINT points to Napcat HTTP API instead of WS route.";
                }
                throw new RuntimeException(message);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException(e);
        }
    }

    private String buildRequestUrl(String path) {
        String normalizedPath = path == null ? "" : path.trim();
        if (normalizedPath.isEmpty()) {
            return httpEndpoint;
        }
        String safePath = normalizedPath.startsWith("/") ? normalizedPath.substring(1) : normalizedPath;
        if (httpEndpoint.endsWith("/")) {
            return httpEndpoint + safePath;
        }
        return httpEndpoint + "/" + safePath;
    }

    private String normalizeEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalStateException("Missing required configuration");
        }

        URI uri = URI.create(endpoint.trim());
        String scheme = uri.getScheme();
        if (scheme == null || scheme.isBlank()) {
            throw new IllegalStateException("Invalid configuration: missing URL scheme");
        }

        String normalizedScheme = scheme;
        if ("ws".equalsIgnoreCase(scheme)) {
            normalizedScheme = "http";
        } else if ("wss".equalsIgnoreCase(scheme)) {
            normalizedScheme = "https";
        }

        String normalizedPath = normalizeEndpointPath(uri.getPath());
        URI normalized = URI.create(normalizedScheme + "://" + uri.getRawAuthority() + normalizedPath);
        return normalized.toString();
    }

    private String normalizeEndpointPath(String path) {
        String rawPath = (path == null || path.isBlank()) ? "" : path.trim();
        String lower = rawPath.toLowerCase();

        if ("/ws".equals(lower) || "/websocket".equals(lower)) {
            return "";
        }

        if (rawPath.endsWith("/")) {
            return rawPath.substring(0, rawPath.length() - 1);
        }
        return rawPath;
    }
}

