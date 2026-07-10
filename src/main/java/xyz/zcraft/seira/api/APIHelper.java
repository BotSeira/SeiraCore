package xyz.zcraft.seira.api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.zcraft.osu.model.BeatmapExtended;
import xyz.zcraft.osu.model.MultiplayerRoom;
import xyz.zcraft.osu.model.User;
import xyz.zcraft.osu.model.UserExtended;
import xyz.zcraft.seira.Seira;
import xyz.zcraft.seira.api.data.*;
import xyz.zcraft.seira.command.ResolutionException;
import xyz.zcraft.seira.command.resolution.ShortcutTarget;
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
import java.util.Map;

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
                    .uri(URI.create(ENDPOINT + "/users/me/friends"))
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
                    .uri(URI.create(ENDPOINT + "/users/me"))
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
        return getBase64BytesResponse("/users/" + uid + "/scores/bestof?" + "n=" + n, "获取最好成绩失败", null);
    }

    public static Response<Base64Bytes> getGroupLeaderboardResponse(ShortcutTarget target, List<Long> uids, String auth) {
        final long beatmapId = lookupBeatmap(target, auth);
        return getBase64BytesResponse(
                "/beatmaps/" + beatmapId + "/leaderboards",
                "获取群排行失败",
                GSON.toJsonTree(Map.of("uids", uids)).toString()
        );
    }

    public static Response<Base64Bytes> getLeaderboardResponse(List<Long> uids) {
        return getBase64BytesResponse(
                "/users/leaderboards",
                "获取排行失败",
                GSON.toJsonTree(Map.of("uids", uids)).toString()
        );
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
                    .uri(URI.create(ENDPOINT + "/multiplayer/rooms/current"))
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

    public static Response<Base64Bytes> getRecentResponse(int n, long uid, boolean includeFail) {
        return getBase64BytesResponse("/users/" + uid + "/scores/recent" + "?n=" + n + "&fail=" + includeFail, "获取最近成绩失败", null);
    }

    public static Response<Base64Bytes> getBeatmapResponse(ShortcutTarget target, String mod, String auth) {
        final long beatmapId = lookupBeatmap(target, auth);
        return getBase64BytesResponse("/beatmaps/" + beatmapId + (mod != null ? "?mod=" + mod : ""), "获取谱面失败", null);
    }

    public static long lookupBeatmap(ShortcutTarget target, String auth) {
        long beatmapId;
        if (!target.isMacro()) {
            beatmapId = target.explicitId();
        } else {
            try {
                final String query = getBeatmapQuery(target);

                HttpRequest localRequest = HttpRequest.newBuilder()
                        .uri(URI.create(ENDPOINT + query))
                        .header("Authorization", "Bearer " + auth)
                        .GET()
                        .build();

                final HttpResponse<String> send = CLIENT.send(localRequest, HttpResponse.BodyHandlers.ofString());

                if (send.statusCode() != 200) {
                    throw parseHttpError(send.body(), send.statusCode(), "查找谱面失败");
                }

                final RawResponse rawResponse = GSON.fromJson(send.body(), RawResponse.class);

                ensureApiSuccess(rawResponse, "查找谱面失败");

                beatmapId = rawResponse.getData().getAsJsonObject().get("beatmap_id").getAsLong();
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        return beatmapId;
    }

    private static String getBeatmapQuery(ShortcutTarget target) {
        String query = "/beatmaps/lookup?";
        if (target.isMacro()) {
            switch (target.macroType().toLowerCase()) {
                case "rs", "bo", "rp" -> {
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
            query = "/beatmap/lookup?m=" + target.explicitId();
        }

        return query;
    }

    public static Response<Base64Bytes> getBeatmapsetResponse(ShortcutTarget target, String auth) {
        final long beatmapsetId = lookupBeatmapset(target, auth);
        return getBase64BytesResponse("/beatmapsets/" + beatmapsetId, "获取谱面集失败", null);
    }

    public static long lookupBeatmapset(ShortcutTarget target, String auth) {
        long beatmapsetId;
        if (!target.isMacro()) {
            beatmapsetId = target.explicitId();
        } else {
            try {
                final String query = getBeatmapsetQuery(target);

                HttpRequest localRequest = HttpRequest.newBuilder()
                        .uri(URI.create(ENDPOINT + query))
                        .header("Authorization", "Bearer " + auth)
                        .GET()
                        .build();

                final HttpResponse<String> send = CLIENT.send(localRequest, HttpResponse.BodyHandlers.ofString());

                if (send.statusCode() != 200) {
                    throw parseHttpError(send.body(), send.statusCode(), "查找谱面集失败");
                }

                final RawResponse rawResponse = GSON.fromJson(send.body(), RawResponse.class);

                ensureApiSuccess(rawResponse, "查找谱面集失败");

                beatmapsetId = rawResponse.getData().getAsJsonObject().get("beatmapset_id").getAsLong();
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        return beatmapsetId;
    }

    private static String getBeatmapsetQuery(ShortcutTarget target) {
        String query = "/beatmapsets/lookup";

        return switch (target.macroType().toLowerCase()) {
            case "m" -> query + "?m=" + target.explicitId();
            case "rs", "bo", "rp" ->
                    query + "?of=" + target.macroType() + "&i=" + target.macroIndex() + "&u=" + target.boundUid();
            case "mp" -> query + "?of=mp";
            case null, default -> throw new ResolutionException("快捷查询格式错误。");
        };
    }

    public static Response<Base64Bytes> getScoreResponse(ShortcutTarget target) {
        long scoreId = lookupScoreId(target);
        return getBase64BytesResponse("/scores/" + scoreId, "获取成绩失败", null);
    }

    public static Response<Base64Bytes> getScoreAnalyzeResponse(ShortcutTarget target) {
        long scoreId = lookupScoreId(target);
        return getBase64BytesResponse("/scores/" + scoreId + "/analysis", "获取成绩分析失败", null);
    }

    public static Response<Base64Bytes> getMissVisualizeResponse(ShortcutTarget target, int index) {
        long scoreId = lookupScoreId(target);
        return getBase64BytesResponse("/scores/" + scoreId + "/misses/" + index + "/visualize", "获取Miss可视化失败", null);
    }

    private static Response<Base64Bytes> getBase64BytesResponse(String query, String failMessage, @Nullable String postBody) {
        try {
            var builder = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT + query));

            if (postBody != null) {
                builder = builder.POST(HttpRequest.BodyPublishers.ofString(postBody));
            } else {
                builder = builder.GET();
            }

            final HttpResponse<byte[]> send = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());

            if (send.statusCode() != 200) {
                throw parseHttpError(send.body(), send.statusCode(), failMessage);
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
        return switch (target.macroType().toLowerCase()) {
            case "rs", "bo", "rp" ->
                    "/scores/lookup?of=" + target.macroType() + "&i=" + target.macroIndex() + "&u=" + target.boundUid();
            case "m" -> "/scores/lookup?m=" + target.explicitId() + "&u=" + target.boundUid();
            case "ms" ->
                    "/scores/lookup?ms=" + target.explicitId() + "&i=" + target.macroIndex() + "&u=" + target.boundUid();
            case null, default -> throw new IllegalArgumentException("Invalid macro type");
        };
    }

    public static Response<?> getLookupBeatmapsetResponse(@NotNull ShortcutTarget target, String s) {
        try {
            final String query = target.isMacro() ? getBeatmapsetQuery(target) : "/beatmapsets/lookup?ms=" + target.explicitId();

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
                    .beatmapsetId(data.get("beatmapset_id").getAsString())
                    .build();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static Response<List<SearchResultItem>> searchBeatmapSetResponse(SearchQuery query) {
        try {
            HttpRequest localRequest = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT + "/beatmapsets/search?" + "q=" + URLEncoder.encode(query.query(), StandardCharsets.UTF_8)))
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

    public static ReplayTaskInfo createReplayShowcaseTask(ShortcutTarget target, String[] groupUids, TimeDurationParser.TimeRange timeRange, String auth) {
        if (groupUids == null || groupUids.length == 0) {
            throw new RuntimeException("同屏回放需要至少一个玩家ID。");
        }

        final long beatmapId = lookupBeatmap(target, auth);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ENDPOINT + "/replays/renders/showcase/" + beatmapId + (timeRange != null ? "?" + timeRange.toQueryString() : "")))
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJsonTree(Map.of("ids", groupUids)).toString()))
                .build();

        return getReplayTaskInfo(request);
    }

    public static ReplayRenderResult waitReplayVideo(String taskId) {
        try {
            waitReplayDone(taskId);
            return new ReplayRenderResult(ENDPOINT + "/replays/" + taskId + "/video/replay.mp4", taskId);
        } catch (RuntimeException _) {
            return null;
        }
    }

    private static ReplayTaskInfo createReplayTask(ShortcutTarget target, TimeDurationParser.TimeRange timeRange) {
        long scoreId = lookupScoreId(target);

        if (timeRange == null) {
            timeRange = getScoreHighlight(scoreId);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ENDPOINT + "/replays/renders/score/" + scoreId + "?" + timeRange.toQueryString()))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        return getReplayTaskInfo(request);
    }

    private static TimeDurationParser.TimeRange getScoreHighlight(long scoreId) {
        try {
            HttpRequest localRequest = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT + "/scores/" + scoreId + "/highlight"))
                    .GET()
                    .build();

            final var send = CLIENT.send(localRequest, HttpResponse.BodyHandlers.ofString());

            if (send.statusCode() != 200) {
                throw parseHttpError(send.body(), send.statusCode(), "高光获取失败");
            }

            final RawResponse rawResponse = GSON.fromJson(send.body(), RawResponse.class);
            ensureApiSuccess(rawResponse, "高光获取失败");
            final JsonObject data = rawResponse.getData().getAsJsonObject();

            return new TimeDurationParser.TimeRange(
                    Math.max(0, (int) (data.get("start").getAsLong() / 1000)) - 5,
                    (int) (data.get("end").getAsLong() / 1000) + 5
            );
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private static long lookupScoreId(ShortcutTarget target) {
        long scoreId;
        if (!target.isMacro()) {
            scoreId = target.explicitId();
        } else {
            try {
                final String query = getScoreQuery(target);

                HttpRequest localRequest = HttpRequest.newBuilder()
                        .uri(URI.create(ENDPOINT + query))
                        .GET()
                        .build();

                final HttpResponse<String> send = CLIENT.send(localRequest, HttpResponse.BodyHandlers.ofString());

                if (send.statusCode() != 200) {
                    throw parseHttpError(send.body(), send.statusCode(), "获取成绩失败");
                }

                final RawResponse rawResponse = GSON.fromJson(send.body(), RawResponse.class);

                ensureApiSuccess(rawResponse, "获取成绩失败");

                scoreId = rawResponse.getData().getAsJsonObject().get("score_id").getAsLong();
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        return scoreId;
    }

    @NotNull
    private static ReplayTaskInfo getReplayTaskInfo(HttpRequest request) {
        try {
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

            final BeatmapExtended beatmap = GSON.fromJson(data.get("beatmap"), BeatmapExtended.class);

            Double start = data.has("start") ? data.get("start").getAsDouble() : null;
            Double end = data.has("end") ? data.get("end").getAsDouble() : null;

            return new ReplayTaskInfo(taskId, status, position, beatmap, data.getAsJsonArray("scores"), start, end);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Replay render request interrupted", e);
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
                    .uri(URI.create(ENDPOINT + "/replays/" + taskId + "/status"))
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
        String message = fallbackMessage;
        try {
            JsonObject root = GSON.fromJson(responseBody, JsonObject.class);
            if (root != null) {
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

        if (statusCode == 500) {
            message += "(" + (errorCode == null ? "未知错误码" : errorCode) + " / HTTP " + statusCode + " / 发生了一个内部错误)";
        }

        return new ApiRequestException(errorCode, message);
    }

    private static RuntimeException parseHttpError(byte[] responseBody, int statusCode, String fallbackMessage) {
        String bodyAsText = responseBody == null ? null : new String(responseBody, StandardCharsets.UTF_8);
        return parseHttpError(bodyAsText, statusCode, fallbackMessage);
    }

    private static Integer extractErrorCode(RawResponse payload) {
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
                    .uri(URI.create(ENDPOINT + "/replays/" + jobId + "/status"))
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

    public static boolean[] getServerStatus() {
        boolean oStella = false;
        boolean osu = false;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT + "/health"))
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

        return new boolean[]{true, oStella, osu};
    }

    public static Response<List<MissData>> getScoreMissesResponse(ShortcutTarget target) {
        final long scoreId = lookupScoreId(target);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT + "/scores/" + scoreId + "/misses"))
                    .GET()
                    .build();

            final HttpResponse<String> send = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (send.statusCode() != 200) {
                throw parseHttpError(send.body(), send.statusCode(), "获取 Miss 数据失败");
            }

            final RawResponse r = GSON.fromJson(send.body(), RawResponse.class);
            ensureApiSuccess(r, "获取 Miss 数据失败");
            final JsonArray data = r.getData().getAsJsonArray();

            List<MissData> misses = new LinkedList<>();
            for (JsonElement datum : data) {
                final JsonObject obj = datum.getAsJsonObject();
                misses.add(new MissData(
                        obj.get("index").getAsInt(),
                        obj.get("time").getAsLong(),
                        MissData.Type.valueOf(obj.get("type").getAsString())
                ));
            }

            return Response.<List<MissData>>fromHeaders(send.headers())
                    .content(misses)
                    .build();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static List<User> getUsers(List<Long> u) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT + "/users"))
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJsonTree(Map.of("ids", u)).toString()))
                    .build();

            final HttpResponse<String> send = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (send.statusCode() != 200) {
                throw parseHttpError(send.body(), send.statusCode(), "获取用户信息失败");
            }

            final RawResponse r = GSON.fromJson(send.body(), RawResponse.class);
            ensureApiSuccess(r, "获取用户信息失败");
            final JsonArray data = r.getData().getAsJsonArray();

            List<User> users = new LinkedList<>();
            for (JsonElement datum : data) {
                users.add(GSON.fromJson(datum, User.class));
            }

            return users;
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public record ReplayRenderResult(String videoUrl, String taskId) {
    }

    public record ReplayTaskInfo(
            String taskId,
            String status,
            Integer position,
            BeatmapExtended beatmap,
            JsonArray scores,
            Double start,
            Double end
    ) {
    }
}
