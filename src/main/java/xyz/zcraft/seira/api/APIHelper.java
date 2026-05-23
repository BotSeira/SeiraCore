package xyz.zcraft.seira.api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import xyz.zcraft.osu.model.MultiplayerRoom;
import xyz.zcraft.osu.model.UserExtended;
import xyz.zcraft.seira.Seira;
import xyz.zcraft.seira.command.ResolutionException;
import xyz.zcraft.seira.command.resolution.ShortcutTarget;
import xyz.zcraft.seira.data.*;
import xyz.zcraft.seira.util.TimeDurationParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedList;
import java.util.List;

public class APIHelper {
    private static final String ENDPOINT;
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofMinutes(5)).build();
    private static final Gson GSON = new Gson();
    private static final int REPLAY_POLL_INTERVAL_MS = 5000;
    private static final int REPLAY_MAX_POLL_ATTEMPTS = 1000;

    static {
        ENDPOINT = Seira.getConfig().ostella().endpoint();
    }

    public static Response<List<FriendEntry>> getFollowed(String accessToken) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT + "/friends"))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();

            final HttpResponse<String> send = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (send.statusCode() != 200) {
                throw parseHttpError(send.body(), send.statusCode(), "获取多人房间失败");
            }

            final RawResponse r = GSON.fromJson(send.body(), RawResponse.class);
            ensureApiSuccess(r, "获取多人房间失败");
            final JsonArray data = r.getData().getAsJsonArray();

            LinkedList<FriendEntry> followed = new LinkedList<>();

            for (JsonElement datum : data) {
                followed.add(GSON.fromJson(datum, FriendEntry.class));
            }

            return Response.<List<FriendEntry>>fromHeaders(send.headers())
                    .content(followed)
                    .build();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static Response<UserExtended> getSelf(String accessToken) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT + "/self"))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();

            final HttpResponse<String> send = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (send.statusCode() != 200) {
                throw parseHttpError(send.body(), send.statusCode(), "获取用户信息失败");
            }

            final RawResponse r = GSON.fromJson(send.body(), RawResponse.class);
            ensureApiSuccess(r, "获取用户信息失败");
            final var data = r.getData().getAsJsonObject();

            return Response.<UserExtended>fromHeaders(send.headers())
                    .content(GSON.fromJson(data, UserExtended.class))
                    .build();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static Response<Base64Bytes> getBoNResponse(int n, long uid) {
        try {
            HttpRequest localRequest = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT + "/bestof?" + "n=" + n + "&u=" + uid))
                    .GET()
                    .build();
            final HttpResponse<byte[]> send = CLIENT.send(localRequest, HttpResponse.BodyHandlers.ofByteArray());

            if (send.statusCode() != 200) {
                throw parseHttpError(send.body(), send.statusCode(), "获取 BoN 失败");
            }

            byte[] imageBytes = send.body();

            return Response.<Base64Bytes>fromHeaders(send.headers())
                    .content(new Base64Bytes(imageBytes))
                    .build();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static Response<Base64Bytes> getGroupLeaderboardResponse(ShortcutTarget target, String[] uids) {
        String uidsParam = String.join(",", uids);
        try {
            String query;
            if (target.isMacro()) {
                query = "/maplb?of=" + target.macroType() + "&i=" + target.macroIndex() + "&us=" + target.boundUid() + "&u=" + uidsParam;
            } else {
                query = "/maplb?m=" + target.explicitId() + "&u=" + uidsParam;
            }

            HttpRequest localRequest = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT + query))
                    .GET()
                    .build();

            final HttpResponse<byte[]> send = CLIENT.send(localRequest, HttpResponse.BodyHandlers.ofByteArray());

            if (send.statusCode() != 200) {
                throw parseHttpError(send.body(), send.statusCode(), "获取群排行失败");
            }

            byte[] imageBytes = send.body();

            return Response.<Base64Bytes>fromHeaders(send.headers())
                    .content(new Base64Bytes(imageBytes))
                    .build();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static Response<Base64Bytes> getLeaderboardResponse(String[] uids) {
        String uidsParam = String.join(",", uids);
        try {
            HttpRequest localRequest = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT + "/leaderboard?" + "u=" + uidsParam))
                    .GET()
                    .build();
            final HttpResponse<byte[]> send = CLIENT.send(localRequest, HttpResponse.BodyHandlers.ofByteArray());

            if (send.statusCode() != 200) {
                throw parseHttpError(send.body(), send.statusCode(), "获取排行失败");
            }

            byte[] imageBytes = send.body();

            return Response.<Base64Bytes>fromHeaders(send.headers())
                    .content(new Base64Bytes(imageBytes))
                    .build();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static String getDaily() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT + "/daily"))
                    .GET()
                    .build();

            final HttpResponse<String> send = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (send.statusCode() != 200) {
                throw parseHttpError(send.body(), send.statusCode(), "获取每日挑战失败");
            }

            final RawResponse r = GSON.fromJson(send.body(), RawResponse.class);
            ensureApiSuccess(r, "获取每日挑战失败");
            final JsonObject data = r.getData().getAsJsonObject();

            String mods = null;
            if (data.has("required_mods") && !data.get("required_mods").isJsonNull())
                mods = data.get("required_mods").getAsString();
            return String.format(
                    """
                            ## 今日挑战
                            > %s
                            > 曲名: %s
                            > 难度: %.2f* %s
                            > 参与人数: %d
                            > 模组: %s""",
                    data.get("name").getAsString(),
                    data.get("title").getAsString(),
                    data.get("difficulty_rating").getAsFloat(),
                    data.get("version").getAsString(),
                    data.get("participant_count").getAsInt(),
                    mods == null || mods.isBlank() ? "NM" : mods
            );
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static Response<MultiplayerRoom> getMultiplayerRoom(String accessToken) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT + "/mp"))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();

            final HttpResponse<String> send = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (send.statusCode() != 200) {
                throw parseHttpError(send.body(), send.statusCode(), "获取多人房间失败");
            }

            final RawResponse r = GSON.fromJson(send.body(), RawResponse.class);
            ensureApiSuccess(r, "获取多人房间失败");
            final JsonObject data = r.getData().getAsJsonObject();

            return Response.<MultiplayerRoom>fromHeaders(send.headers())
                    .content(GSON.fromJson(data, MultiplayerRoom.class))
                    .build();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static Response<Base64Bytes> getRecentResponse(int n, long uid) {
        try {
            HttpRequest localRequest = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT + "/recent?" + "n=" + n + "&u=" + uid))
                    .GET()
                    .build();

            final HttpResponse<byte[]> send = CLIENT.send(localRequest, HttpResponse.BodyHandlers.ofByteArray());

            if (send.statusCode() != 200) {
                throw parseHttpError(send.body(), send.statusCode(), "获取最近成绩失败");
            }

            byte[] imageBytes = send.body();

            return Response.<Base64Bytes>fromHeaders(send.headers())
                    .content(new Base64Bytes(imageBytes))
                    .build();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static Response<Base64Bytes> getBeatmapResponse(ShortcutTarget target, String mod, String auth) {
        try {
            final String query = getBeatmapQuery(target, mod);

            HttpRequest localRequest = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT + query))
                    .header("Authorization", "Bearer " + auth)
                    .GET()
                    .build();

            final HttpResponse<byte[]> send = CLIENT.send(localRequest, HttpResponse.BodyHandlers.ofByteArray());

            if (send.statusCode() != 200) {
                throw parseHttpError(send.body(), send.statusCode(), "获取谱面失败");
            }

            byte[] imageBytes = send.body();

            return Response.<Base64Bytes>fromHeaders(send.headers())
                    .content(new Base64Bytes(imageBytes))
                    .build();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private static String getBeatmapQuery(ShortcutTarget target, String mod) {
        String query = "/beatmap?";
        if (target.isMacro()) {
            switch (target.macroType()) {
                case "rs", "bo" -> {
                    query += "&of=" + target.macroType() + "&u=" + target.boundUid();
                    query += "&i=" + target.macroIndex();
                }
                case "ms" -> {
                    query += "&ms=" + target.explicitId();
                    query += "&i=" + target.macroIndex();
                }
                case "mp" -> query += "&of=mp";
            }
        } else {
            query = "/beatmap?m=" + target.explicitId();
        }

        if (mod != null) {
            query += "&mod=" + mod;
        }

        return query;
    }

    public static Response<Base64Bytes> getBeatmapsetResponse(ShortcutTarget target, String auth) {
        try {
            final String query = getBeatmapsetQuery(target, "beatmapset");

            HttpRequest localRequest = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT + query))
                    .header("Authorization", "Bearer " + auth)
                    .GET()
                    .build();

            final HttpResponse<byte[]> send = CLIENT.send(localRequest, HttpResponse.BodyHandlers.ofByteArray());

            if (send.statusCode() != 200) {
                throw parseHttpError(send.body(), send.statusCode(), "获取谱面集失败");
            }

            byte[] imageBytes = send.body();

            return Response.<Base64Bytes>fromHeaders(send.headers())
                    .content(new Base64Bytes(imageBytes))
                    .build();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private static String getBeatmapsetQuery(ShortcutTarget target, String prefix) {
        String query = null;
        if (target.isMacro()) {
            if ("m".equals(target.macroType())) {
                query = "/" + prefix + "?m=" + target.explicitId();
            } else if ("bo".equals(target.macroType()) || "rs".equals(target.macroType())) {
                query = "/" + prefix + "?of=" + target.macroType() + "&i=" + target.macroIndex() + "&u=" + target.boundUid();
            } else if ("mp".equals(target.macroType())) {
                query = "/" + prefix + "?of=mp";
            }
        } else {
            query = "/" + prefix + "?ms=" + target.explicitId();
        }

        if (query == null) {
            throw new ResolutionException("快捷查询格式错误。");
        }
        return query;
    }

    public static Response<Base64Bytes> getScoreResponse(ShortcutTarget target) {
        try {
            final String query = getScoreQuery(target);

            HttpRequest localRequest = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT + query))
                    .GET()
                    .build();

            final HttpResponse<byte[]> send = CLIENT.send(localRequest, HttpResponse.BodyHandlers.ofByteArray());

            if (send.statusCode() != 200) {
                throw parseHttpError(send.body(), send.statusCode(), "获取成绩失败");
            }

            byte[] imageBytes = send.body();

            return Response.<Base64Bytes>fromHeaders(send.headers())
                    .content(new Base64Bytes(imageBytes))
                    .build();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private static String getScoreQuery(ShortcutTarget target) {
        String query;
        if (target.isMacro()) {
            query = switch (target.macroType()) {
                case "bo", "rs" ->
                        "/score?of=" + target.macroType() + "&i=" + target.macroIndex() + "&u=" + target.boundUid();
                case "m" -> "/score?m=" + target.explicitId() + "&u=" + target.boundUid();
                case "ms" ->
                        "/score?ms=" + target.explicitId() + "&i=" + target.macroIndex() + "&u=" + target.boundUid();
                case null, default -> throw new IllegalArgumentException("Invalid macro type");
            };
        } else {
            query = "/score?s=" + target.explicitId();
        }
        return query;
    }

    public static Response<List<SearchResultItem>> searchBeatmapSetResponse(SearchQuery query) {
        try {
            HttpRequest localRequest = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT + "/searchms?" + "q=" + URLEncoder.encode(query.query(), StandardCharsets.UTF_8)))
                    .GET()
                    .build();

            final var send = CLIENT.send(localRequest, HttpResponse.BodyHandlers.ofString());

            if (send.statusCode() != 200) {
                throw parseHttpError(send.body(), send.statusCode(), "搜索谱面集失败");
            }

            final RawResponse rawResponse = GSON.fromJson(send.body(), RawResponse.class);
            ensureApiSuccess(rawResponse, "搜索谱面集失败");
            final JsonArray data = rawResponse.getData().getAsJsonArray();

            final LinkedList<SearchResultItem> items = new LinkedList<>();

            data.forEach(item -> items.add(GSON.fromJson(item, SearchResultItem.class)));

            return Response.<List<SearchResultItem>>fromHeaders(send.headers())
                    .content(items)
                    .build();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static ReplayTaskInfo createReplayRenderTask(ShortcutTarget target, TimeDurationParser.TimeRange timeRange) {
        return createReplayTask(target, timeRange);
    }

    public static ReplayTaskInfo createShowcaseRenderTaskByBeatmap(Long beatmapId, String[] uids, TimeDurationParser.TimeRange timeRange) {
        if (beatmapId == null || beatmapId <= 0) {
            throw new RuntimeException("谱面ID无效。");
        }
        if (uids == null || uids.length == 0) {
            throw new RuntimeException("回放渲染需要至少一个玩家ID。");
        }

        String uidsParam = String.join(",", uids);
        return createReplayTask("/replay/showcase?m=" + beatmapId + "&u=" + uidsParam + (timeRange != null ? timeRange.toQueryString() : ""));
    }

    public static ReplayTaskInfo createReplayShowcaseTask(ShortcutTarget target, String[] groupUids, TimeDurationParser.TimeRange timeRange) {
        if (!target.isMacro() || target.boundUid() == null) {
            throw new RuntimeException("同屏回放仅支持玩家快捷查询（如 rs1/bo1）。");
        }
        if (groupUids == null || groupUids.length == 0) {
            throw new RuntimeException("同屏回放需要至少一个玩家ID。");
        }

        String groupUidsParam = String.join(",", groupUids);
        String query = "/replay/showcase?of=" + target.macroType()
                + "&i=" + target.macroIndex()
                + "&us=" + target.boundUid()
                + "&u=" + groupUidsParam;

        if(timeRange != null) {
            query += timeRange.toQueryString();
        }

        return createReplayTask(query);
    }

    public static ReplayRenderResult waitReplayVideo(String taskId) {
        try {
            waitReplayDone(taskId);
            return new ReplayRenderResult(ENDPOINT + "/replay/video/" + taskId + "/replay.mp4", taskId);
        } catch (RuntimeException _) {
            return null;
        }
    }

    private static ReplayTaskInfo createReplayTask(ShortcutTarget target, TimeDurationParser.TimeRange timeRange) {
        return createReplayTask(getReplayRenderQuery(target, timeRange));
    }

    private static ReplayTaskInfo createReplayTask(String query) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT + query))
                    .GET()
                    .build();
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            RawResponse payload = GSON.fromJson(response.body(), RawResponse.class);
            if (codeNotOk(response.statusCode())) {
                throw parseHttpError(response.body(), response.statusCode(), "回放渲染请求失败");
            }
            ensureApiSuccess(payload, "回放渲染请求失败");
            JsonObject data = requireDataObject(payload, "回放渲染请求缺少任务信息");
            if (!data.has("id") || data.get("id").isJsonNull()) {
                throw new RuntimeException("回放渲染请求缺少任务ID");
            }
            String taskId = data.get("id").getAsString();

            String status = data.has("status") && !data.get("status").isJsonNull()
                    ? data.get("status").getAsString()
                    : null;

            Integer position = data.has("position") && !data.get("position").isJsonNull()
                    ? data.get("position").getAsInt()
                    : null;

            return new ReplayTaskInfo(taskId, status, position, buildReplayTaskMessage(data));
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Replay render request interrupted", e);
        }
    }

    private static String buildReplayTaskMessage(JsonObject data) {
        StringBuilder sb = new StringBuilder();
        if (data.has("beatmap") && data.get("beatmap").isJsonObject()) {
            JsonObject beatmap = data.getAsJsonObject("beatmap");
            sb.append("谱面: ");
            sb.append("%d - %s - %s [%.2f★ %s]".formatted(
                    beatmap.get("id").getAsLong(),
                    beatmap.get("artist").getAsString(),
                    beatmap.get("title").getAsString(),
                    beatmap.get("star").getAsDouble(),
                    beatmap.get("version").getAsString()
            ));
        }

        if (data.has("scores") && data.get("scores").isJsonArray()) {
            JsonArray scores = data.getAsJsonArray("scores");
            sb.append("共%d个成绩:".formatted(scores.size()));

            for (JsonElement element : scores) {
                if (!element.isJsonObject()) {
                    continue;
                }
                String line = buildScoreLine(element.getAsJsonObject());
                if (line == null) {
                    continue;
                }
                if (!sb.isEmpty()) {
                    sb.append("\n");
                }
                sb.append(line);
            }
        }

        return sb.isEmpty() ? null : sb.toString();
    }

    private static String buildScoreLine(JsonObject score) {
        String username = getScoreField(score, "username");
        String rank = getScoreField(score, "rank");
        String accuracy = getScoreField(score, "accuracy");
        String pp = getScoreField(score, "pp");

        if (username == null && rank == null && accuracy == null && pp == null) {
            return null;
        }

        return "%s / %s / %s / %s".formatted(username, rank, accuracy, pp);
    }

    private static String getScoreField(JsonObject score, String field) {
        if (score == null || !score.has(field) || score.get(field).isJsonNull()) {
            return null;
        }
        try {
            return score.get(field).getAsString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void waitReplayDone(String taskId) {
        for (int attempt = 1; attempt <= REPLAY_MAX_POLL_ATTEMPTS; attempt++) {
            String status = getReplayStatus(taskId);
            if ("done".equalsIgnoreCase(status)) {
                return;
            }
            if ("failed".equalsIgnoreCase(status) || "canceled".equalsIgnoreCase(status)) {
                throw new RuntimeException("回放渲染失败，状态：" + status);
            }
            try {
                Thread.sleep(REPLAY_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Replay status polling interrupted", e);
            }
        }

        throw new RuntimeException("回放渲染超时，请稍后重试。");
    }

    private static String getReplayStatus(String taskId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT + "/replay/status/" + taskId))
                    .GET()
                    .build();
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            RawResponse payload = GSON.fromJson(response.body(), RawResponse.class);
            if (codeNotOk(response.statusCode())) {
                throw parseHttpError(response.body(), response.statusCode(), "查询回放渲染状态失败");
            }
            ensureApiSuccess(payload, "查询回放渲染状态失败");
            JsonObject data = requireDataObject(payload, "回放渲染状态响应缺少data");
            if (!data.has("status") || data.get("status").isJsonNull()) {
                throw new RuntimeException("回放渲染状态响应缺少status");
            }
            return data.get("status").getAsString();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Replay status request interrupted", e);
        }
    }

    private static String getReplayRenderQuery(ShortcutTarget target, TimeDurationParser.TimeRange timeRange) {
        if (target.isMacro()) {
            if (target.boundUid() == null) {
                throw new RuntimeException("回放仅支持玩家快捷查询（如 rs1/bo1）或成绩ID。");
            }
            return "/replay/render?of=" + target.macroType() + "&i=" + target.macroIndex() + "&u=" + target.boundUid() + (timeRange != null ? timeRange.toQueryString() : "");
        }
        return "/replay/render?s=" + target.explicitId() + (timeRange != null ? timeRange.toQueryString() : "");
    }

    private static void ensureApiSuccess(RawResponse payload, String fallbackMessage) {
        if (payload == null) {
            throw new RuntimeException(fallbackMessage);
        }
        if (!payload.isSuccess()) {
            Integer errorCode = extractErrorCode(payload);
            String message = payload.getMessage() != null ? payload.getMessage() : fallbackMessage;
            throw new ApiRequestException(errorCode, message);
        }
    }

    private static RuntimeException parseHttpError(String responseBody, int statusCode, String fallbackMessage) {
        Integer errorCode = null;
        String message = null;
        try {
            JsonObject root = GSON.fromJson(responseBody, JsonObject.class);
            if (root != null) {
                if (root.has("message") && root.get("message").isJsonPrimitive()) {
                    message = root.get("message").getAsString();
                }
                // Error code is primarily returned as Response.data.code.
                if (root.has("data") && root.get("data").isJsonObject()) {
                    JsonObject data = root.getAsJsonObject("data");
                    errorCode = readCodeFromJsonObject(data);
                }
                if (errorCode == null) {
                    errorCode = readCodeFromJsonObject(root);
                }
            }
        } catch (Exception ignored) {
        }

        String resolvedMessage = (message != null && !message.isBlank())
                ? message
                : fallbackMessage + "（HTTP " + statusCode + "）";
        return new ApiRequestException(errorCode, resolvedMessage);
    }

    private static RuntimeException parseHttpError(byte[] responseBody, int statusCode, String fallbackMessage) {
        String bodyAsText = responseBody == null ? null : new String(responseBody, StandardCharsets.UTF_8);
        return parseHttpError(bodyAsText, statusCode, fallbackMessage);
    }

    private static Integer extractErrorCode(RawResponse payload) {
        // Error code is returned as Response.data.code.
        if (payload.getData() != null && payload.getData().isJsonObject()) {
            JsonObject data = payload.getData().getAsJsonObject();
            return readCodeFromJsonObject(data);
        }
        return null;
    }

    private static boolean codeNotOk(int statusCode) {
        return statusCode < 200 || statusCode >= 300;
    }

    private static Integer readCodeFromJsonObject(JsonObject object) {
        if (object == null || !object.has("code") || !object.get("code").isJsonPrimitive()) {
            return null;
        }
        try {
            return object.get("code").getAsInt();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static JsonObject requireDataObject(RawResponse payload, String message) {
        if (payload.getData() == null || !payload.getData().isJsonObject()) {
            throw new RuntimeException(message);
        }
        return payload.getData().getAsJsonObject();
    }

    public static RenderStat getRenderStat(String jobId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT + "/replay/status/" + jobId))
                    .GET()
                    .build();

            final HttpResponse<String> send = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (send.statusCode() != 200) {
                throw parseHttpError(send.body(), send.statusCode(), "获取渲染进度失败");
            }

            final RawResponse r = GSON.fromJson(send.body(), RawResponse.class);
            ensureApiSuccess(r, "获取渲染进度失败");
            final JsonObject data = r.getData().getAsJsonObject();

            return GSON.fromJson(data, RenderStat.class);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static String getServerStatus() {
        boolean oStella = false;
        boolean osu = false;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT + "/status"))
                    .GET()
                    .build();

            final HttpResponse<String> send = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            final RawResponse rawResponse = GSON.fromJson(send.body(), RawResponse.class);
            if (send.statusCode() == 200
                    && send.body() != null
                    && rawResponse != null
                    && rawResponse.isSuccess()) {
                oStella = true;

                if (rawResponse.getData() != null && rawResponse.getData().isJsonObject()) {
                    JsonObject data = rawResponse.getData().getAsJsonObject();
                    if (data.has("osu-api") && !data.get("osu-api").isJsonNull()) {
                        osu = data.get("osu-api").getAsBoolean();
                    }
                }
            }
        } catch (Exception _) {
        }

        StringBuilder sb = new StringBuilder();
        sb.append("服务器状态: \n");
        sb.append("消息网关: ✅ 正常\n");
        sb.append("oStella API: ").append(oStella ? "✅ 正常" : "❌ 无法访问").append("\n");

        if (oStella) {
            sb.append("osu! API: ").append(osu ? "✅ 正常" : "❌ 无法访问").append("\n");
        }

        return sb.toString().trim();

    }

    public static Response<?> lookupBeatmapset(ShortcutTarget target, String s) {
        try {
            final String query = getBeatmapsetQuery(target, "lookup/beatmapset");

            HttpRequest localRequest = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT + query))
                    .header("Authorization", "Bearer " + s)
                    .GET()
                    .build();

            final HttpResponse<String> send = CLIENT.send(localRequest, HttpResponse.BodyHandlers.ofString());

            if (send.statusCode() != 200) {
                throw parseHttpError(send.body(), send.statusCode(), "获取谱面集失败");
            }

            final RawResponse rawResponse = GSON.fromJson(send.body(), RawResponse.class);
            ensureApiSuccess(rawResponse, "查找谱面集失败");
            final JsonObject data = rawResponse.getData().getAsJsonObject();

            return Response.<Void>fromHeaders(send.headers())
                    .beatmapsetId(data.get("id").getAsString())
                    .build();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public record ReplayRenderResult(String videoUrl, String taskId) {
    }

    public record ReplayTaskInfo(String taskId, String status, Integer position, String message) {
    }
}
