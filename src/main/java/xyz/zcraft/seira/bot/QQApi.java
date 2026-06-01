package xyz.zcraft.seira.bot;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.seira.data.FileInfo;
import xyz.zcraft.seira.data.Message;
import xyz.zcraft.seira.util.AccessToken;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class QQApi {
    private static final String ENDPOINT = "https://api.sgroup.qq.com";
    private static final HttpClient CLIENT = HttpClient.newBuilder().build();
    private static final Gson gson = new Gson();
    private static final Logger LOG = LogManager.getLogger(QQApi.class);


    public static String getWSSEndpoint(AccessToken accessToken) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(new URI(ENDPOINT + "/gateway"))
                    .header("Authorization", "QQBot " + accessToken.token())
                    .GET()
                    .build();

            return JsonParser.parseString(CLIENT.send(request, HttpResponse.BodyHandlers.ofString()).body())
                    .getAsJsonObject()
                    .get("url")
                    .getAsString();
        } catch (IOException | InterruptedException | URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    public static AccessToken getAccessToken(String appId, String appSecret) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("appId", appId);
        payload.addProperty("clientSecret", appSecret);

        HttpRequest request = HttpRequest.newBuilder()
                .header("Content-Type", "application/json")
                .uri(URI.create("https://bots.qq.com/app/getAppAccessToken"))
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        final JsonElement jsonElement = JsonParser.parseString(CLIENT.send(request, HttpResponse.BodyHandlers.ofString()).body());

        return new AccessToken(
                jsonElement.getAsJsonObject().get("access_token").getAsString(),
                System.currentTimeMillis(),
                jsonElement.getAsJsonObject().get("expires_in").getAsLong()
        );
    }

    public static void sendPrivateMessage(AccessToken accessToken, String openId, Message message) {
        try {
            final var request = newRequestBuilder(accessToken)
                    .uri(URI.create(ENDPOINT + "/v2/users/" + openId + "/messages"))
                    .POST(HttpRequest.BodyPublishers.ofString(buildMessageJson(message)))
                    .build();

            final HttpResponse<String> send = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (send.statusCode() != 200) {
                throw new RuntimeException("Failed to send private message to " + openId + " " + send.body());
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static void sendGroupMessage(AccessToken accessToken, String groupId, Message message) {
        try {
            final var request = newRequestBuilder(accessToken)
                    .uri(URI.create(ENDPOINT + "/v2/groups/" + groupId + "/messages"))
                    .POST(HttpRequest.BodyPublishers.ofString(buildMessageJson(message)))
                    .build();

            final HttpResponse<String> send = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (send.statusCode() != 200) {
                throw new RuntimeException("Failed to send group message to " + groupId + " " + send.body());
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static String buildMessageJson(Message message) {
        final JsonObject asJsonObject = new Gson().toJsonTree(message).getAsJsonObject();

        return asJsonObject.toString();
    }

    public static FileInfo uploadPrivateMedia(AccessToken accessToken, String openId, int fileType, String url) {
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("file_type", fileType);
            payload.addProperty("url", url);
            payload.addProperty("srv_send_msg", false);

            final var request = newRequestBuilder(accessToken)
                    .uri(URI.create(ENDPOINT + "/v2/users/" + openId + "/files"))
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();

            final HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            LOG.debug("Upload private media response: status={}", response.statusCode());
            return parseUploadedFileInfo(response, "upload private media");
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static FileInfo uploadGroupMedia(AccessToken accessToken, String openId, int fileType, String url) {
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("file_type", fileType);
            payload.addProperty("url", url);
            payload.addProperty("srv_send_msg", false);

            final var request = newRequestBuilder(accessToken)
                    .uri(URI.create(ENDPOINT + "/v2/groups/" + openId + "/files"))
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();

            final HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOG.error("Failed to upload group media! Status code: {} body={}", response.statusCode(), response.body());
                throw new RuntimeException("Failed to upload group media! Status code: " + response.statusCode());
            }

            LOG.debug("Upload group media status={}", response.statusCode());

            return parseUploadedFileInfo(response, "upload private media");
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static FileInfo uploadPrivateMediaBase64(AccessToken accessToken, String openId, int fileType, String base64) {
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("file_type", fileType);
            payload.addProperty("file_data", base64);
            payload.addProperty("srv_send_msg", false);

            final var request = newRequestBuilder(accessToken)
                    .uri(URI.create(ENDPOINT + "/v2/users/" + openId + "/files"))
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();

            final HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            return parseUploadedFileInfo(response, "upload private media(base64)");
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static FileInfo uploadGroupMediaBase64(AccessToken accessToken, String openId, int fileType, String base64) {
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("file_type", fileType);
            payload.addProperty("file_data", base64);
            payload.addProperty("srv_send_msg", false);

            final var request = newRequestBuilder(accessToken)
                    .uri(URI.create(ENDPOINT + "/v2/groups/" + openId + "/files"))
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();

            final HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            return parseUploadedFileInfo(response, "upload private media(base64)");
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private static FileInfo parseUploadedFileInfo(HttpResponse<String> response, String action) {
        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to " + action + "! Status code: " + response.statusCode() + " body=" + response.body());
        }

        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonObject data = root.has("data") && root.get("data").isJsonObject()
                ? root.getAsJsonObject("data")
                : root;

        FileInfo fileInfo = gson.fromJson(data, FileInfo.class);
        if (fileInfo == null || fileInfo.getFileInfo() == null || fileInfo.getFileInfo().isBlank()) {
            throw new RuntimeException("Failed to " + action + ": missing file_info in response body=" + response.body());
        }
        return fileInfo;
    }

    private static HttpRequest.Builder newRequestBuilder(AccessToken accessToken) {
        return HttpRequest.newBuilder()
                .header("Authorization", "QQBot " + accessToken.token())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
    }
}
