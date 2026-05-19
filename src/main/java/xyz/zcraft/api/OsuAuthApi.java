package xyz.zcraft.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.data.OsuToken;
import xyz.zcraft.model.OsuUser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class OsuAuthApi {
    private static final Gson GSON = new Gson();
    private static final Logger LOG = LogManager.getLogger(OsuAuthApi.class);

    public static OsuToken getTokenFromCode(String code, int clientId, String clientSecret) {
        try (final HttpClient client = HttpClient.newHttpClient()) {
            final JsonObject body = new JsonObject();
            body.addProperty("client_id", clientId);
            body.addProperty("client_secret", clientSecret);
            body.addProperty("code", code);
            body.addProperty("grant_type", "authorization_code");
            body.addProperty("scope", "delegate,identify,public");

            final HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://osu.ppy.sh/oauth/token"))
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .header("Content-Type", "application/json")
                    .build();

            final HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new Exception("Failed to get token from Osu API, response code: " + response.statusCode());
            }

            final JsonObject responseJson = JsonParser.parseString(response.body()).getAsJsonObject();

            return new OsuToken(
                    responseJson.get("access_token").getAsString(),
                    responseJson.get("refresh_token").getAsString(),
                    responseJson.get("expires_in").getAsLong(),
                    System.currentTimeMillis()
            );
        } catch (Exception ex) {
            LOG.error("Failed to get token from Osu API", ex);
            return null;
        }
    }

    public static OsuToken refreshToken(OsuToken token, int clientId, String clientSecret) {
        try (final HttpClient client = HttpClient.newHttpClient()) {
            final JsonObject body = new JsonObject();
            body.addProperty("client_id", clientId);
            body.addProperty("client_secret", clientSecret);
            body.addProperty("grant_type", "refresh_token");
            body.addProperty("refresh_token", token.refreshToken());

            final HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://osu.ppy.sh/oauth/token"))
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .header("Content-Type", "application/json")
                    .build();

            final HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new Exception("Failed to refresh token from Osu API, response code: " + response.statusCode());
            }

            final JsonObject responseJson = JsonParser.parseString(response.body()).getAsJsonObject();

            return new OsuToken(
                    responseJson.get("access_token").getAsString(),
                    responseJson.get("refresh_token").getAsString(),
                    responseJson.get("expires_in").getAsLong(),
                    System.currentTimeMillis()
            );
        } catch (Exception ex) {
            LOG.error("Failed to refresh token from Osu API", ex);
            return null;
        }
    }

    public static OsuUser getUserFromToken(OsuToken token) {
        try (final HttpClient client = HttpClient.newHttpClient()) {
            final HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://osu.ppy.sh/api/v2/me"))
                    .GET()
                    .header("Authorization", "Bearer " + token.accessToken())
                    .build();

            final HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new Exception("Failed to get user info from Osu API, response code: " + response.statusCode());
            }

            final JsonObject responseJson = JsonParser.parseString(response.body()).getAsJsonObject();
            return GSON.fromJson(responseJson, OsuUser.class);
        } catch (Exception ex) {
            LOG.error("Failed to get user info from Osu API", ex);
            return null;
        }
    }
}
