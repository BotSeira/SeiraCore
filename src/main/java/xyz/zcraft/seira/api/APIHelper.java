package xyz.zcraft.seira.api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.zcraft.osu.model.*;
import xyz.zcraft.seira.Seira;
import xyz.zcraft.seira.api.data.*;
import xyz.zcraft.seira.command.ResolutionException;
import xyz.zcraft.seira.command.parse.ShortcutTarget;
import xyz.zcraft.seira.bot.data.FileInfo;
import xyz.zcraft.seira.data.UserRef;
import xyz.zcraft.seira.util.TimeDurationParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

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
            throw requestFailure(e);
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
            throw requestFailure(e);
        }
    }

    public static Response<Base64Bytes> getBoNResponse(int n, UserRef userRef) {
        return getBoNResponse(n, userRef, List.of());
    }

    public static Response<Base64Bytes> getBoNResponse(int n, UserRef userRef, List<String> filters) {
        long uid = resolveUid(userRef);
        return getBase64BytesResponse(
                "/users/" + uid + "/scores/bestof?n=" + n + encodeScoreFilters(filters),
                "获取最好成绩失败",
                null
        );
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
            throw requestFailure(e);
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
            throw requestFailure(e);
        }
    }

    public static Response<Base64Bytes> getRecentResponse(int n, UserRef userRef, boolean includeFail) {
        return getRecentResponse(n, userRef, includeFail, List.of());
    }

    public static Response<Base64Bytes> getRecentResponse(int n, UserRef userRef, boolean includeFail, List<String> filters) {
        long uid = resolveUid(userRef);
        return getBase64BytesResponse(
                "/users/" + uid + "/scores/recent?n=" + n + "&fail=" + includeFail + encodeScoreFilters(filters),
                "获取最近成绩失败",
                null
        );
    }

    private static String encodeScoreFilters(List<String> filters) {
        if (filters == null || filters.isEmpty()) {
            return "";
        }
        return "&filters=" + URLEncoder.encode(String.join(",", filters), StandardCharsets.UTF_8);
    }

    public static Response<Base64Bytes> getBeatmapResponse(ShortcutTarget target, String mod, String auth) {
        final long beatmapId = lookupBeatmap(target, auth);
        return getBase64BytesResponse("/beatmaps/" + beatmapId + (mod != null ? "?mod=" + mod : ""), "获取谱面失败", null);
    }

    public static Response<Base64Bytes> getBeatmapsetBgResponse(ShortcutTarget target, String auth) {
        final long beatmapsetId = lookupBeatmapset(target, auth);
        return getBase64BytesResponse("/beatmapsets/" + beatmapsetId + "/background", "获取谱面集失败", null);
    }

    public static Response<Base64Bytes> getBeatmapBgResponse(ShortcutTarget target, String auth) {
        final long beatmapId = lookupBeatmap(target, auth);
        return getBase64BytesResponse("/beatmaps/" + beatmapId + "/background", "获取谱面失败", null);
    }

    public static long lookupBeatmap(ShortcutTarget target, String auth) {
        long beatmapId;
        if (target.isLocalScore()) {
            beatmapId = lookupScoreData(target.localScoreId()).get("beatmap_id").getAsLong();
        } else if (!target.isMacro()) {
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
                throw requestFailure(e);
            }
        }
        return beatmapId;
    }

    private static String getBeatmapQuery(ShortcutTarget target) {
        String query = "/beatmaps/lookup?";
        if (target.isMacro()) {
            switch (target.macroType().toLowerCase()) {
                case "rs", "bo", "rp" -> {
                    query += "&of=" + target.macroType() + "&u=" + resolveUid(target.userRef());
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

    public static Beatmapset getBeatmapsetRaw(long id) {
        try {
            var builder = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT + "/beatmapsets/" + id))
                    .header("Accept", "application/json");

            final HttpResponse<String> send = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());

            if (send.statusCode() != 200) {
                throw parseHttpError(send.body(), send.statusCode(), "获取谱面集失败");
            }

            final RawResponse r = GSON.fromJson(send.body(), RawResponse.class);
            ensureApiSuccess(r, "获取谱面集失败");
            final JsonObject data = r.getData().getAsJsonObject();

            return GSON.fromJson(data, Beatmapset.class);
        } catch (IOException | InterruptedException e) {
            throw requestFailure(e);
        }
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
                throw requestFailure(e);
            }
        }
        return beatmapsetId;
    }

    private static String getBeatmapsetQuery(ShortcutTarget target) {
        String query = "/beatmapsets/lookup";

        return switch (target.macroType().toLowerCase()) {
            case "m" -> query + "?m=" + target.explicitId();
            case "rs", "bo", "rp" ->
                    query + "?of=" + target.macroType() + "&i=" + target.macroIndex() + "&u=" + resolveUid(target.userRef());
            case "mp" -> query + "?of=mp";
            case null, default -> throw new ResolutionException("快捷查询格式错误。");
        };
    }

    public static Response<Base64Bytes> getScoreResponse(ShortcutTarget target) {
        String scoreId = lookupScoreId(target);
        return getBase64BytesResponse("/scores/" + scoreId, "获取成绩失败", null);
    }

    public static Response<Base64Bytes> getScoreAnalyzeResponse(ShortcutTarget target) {
        String scoreId = lookupScoreId(target);
        return getBase64BytesResponse("/scores/" + scoreId + "/analysis", "获取成绩分析失败", null);
    }

    public static Response<Base64Bytes> getMissVisualizeResponse(ShortcutTarget target, int index) {
        String scoreId = lookupScoreId(target);
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
            throw requestFailure(e);
        }
    }

    private static String getScoreQuery(ShortcutTarget target) {
        return switch (target.macroType().toLowerCase()) {
            case "rs", "bo", "rp" ->
                    "/scores/lookup?of=" + target.macroType() + "&i=" + target.macroIndex() + "&u=" + resolveUid(target.userRef());
            case "m" -> "/scores/lookup?m=" + target.explicitId() + "&u=" + resolveUid(target.userRef());
            case "ms" ->
                    "/scores/lookup?ms=" + target.explicitId() + "&i=" + target.macroIndex() + "&u=" + resolveUid(target.userRef());
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
            throw requestFailure(e);
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
            throw requestFailure(e);
        }
    }

    public static ReplayTaskInfo createReplayRenderTask(ShortcutTarget target, TimeDurationParser.TimeRange timeRange) {
        return createReplayTask(target, timeRange, null);
    }

    public static ReplayTaskInfo createReplayRenderTask(ShortcutTarget target,
                                                         TimeDurationParser.TimeRange timeRange,
                                                         QqUploadRequest qqUpload) {
        return createReplayTask(target, timeRange, qqUpload);
    }

    public static ReplayTaskInfo createObscuredReplayRenderTask(long scoreId) {
        return createObscuredReplayRenderTask(scoreId, null);
    }

    public static ReplayTaskInfo createObscuredReplayRenderTask(long scoreId, QqUploadRequest qqUpload) {
        if (scoreId <= 0) {
            throw new IllegalArgumentException("成绩ID必须为正整数");
        }

        TimeDurationParser.TimeRange timeRange = getScoreHighlight(scoreId, 10);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ENDPOINT + "/replays/renders/score/" + scoreId
                        + "?obscured=true" + timeRange.toQueryString()))
                .header("Content-Type", "application/json")
                .POST(renderRequestBody(qqUpload))
                .build();

        return getReplayTaskInfo(request);
    }

    public static RandomScore getRandomScore() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT + "/scores/random?min_rank=500000"))
                    .GET()
                    .build();

            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (codeNotOk(response.statusCode())) {
                throw parseHttpError(response.body(), response.statusCode(), "获取随机成绩失败");
            }

            RawResponse payload = GSON.fromJson(response.body(), RawResponse.class);
            ensureApiSuccess(payload, "获取随机成绩失败");
            JsonObject data = requireDataObject(payload, "随机成绩响应缺少data");
            if (!data.has("user") || !data.get("user").isJsonObject()
                    || !data.has("score") || !data.get("score").isJsonObject()) {
                throw new RuntimeException("随机成绩响应缺少用户或成绩数据");
            }

            return new RandomScore(
                    GSON.fromJson(data.getAsJsonObject("user"), UserExtended.class),
                    GSON.fromJson(data.getAsJsonObject("score"), Score.class),
                    data.get("best_index").getAsInt(),
                    data.get("diff").getAsString()
            );
        } catch (IOException e) {
            throw requestFailure(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("随机成绩请求被中断", e);
        }
    }

    public static ReplayTaskInfo createReplayShowcaseTask(ShortcutTarget target, String[] ids, String auth) {
        return createReplayShowcaseTask(target, ids, auth, null);
    }

    public static ReplayTaskInfo createBeatmapPreviewTask(ShortcutTarget target, String mods, String auth,
                                                           QqUploadRequest qqUpload) {
        long beatmapId = lookupBeatmap(target, auth);
        JsonObject body = new JsonObject();
        if (mods != null && !mods.isBlank()) {
            body.addProperty("mods", mods);
        }
        if (qqUpload != null) {
            body.add("qqUpload", GSON.toJsonTree(qqUpload));
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ENDPOINT + "/replays/renders/preview/" + beatmapId))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return getReplayTaskInfo(request);
    }

    public static ReplayTaskInfo createReplayShowcaseTask(ShortcutTarget target, String[] ids, String auth,
                                                           QqUploadRequest qqUpload) {
        ids = ids == null ? new String[0] : ids;

        if (target.isLocalScore()) {
            ids = Stream.concat(Stream.of("s" + target.localScoreId()), Arrays.stream(ids))
                    .distinct()
                    .toArray(String[]::new);
        }
        if (ids.length == 0) {
            throw new RuntimeException("同屏回放需要至少一个ID。");
        }

        final long beatmapId = lookupBeatmap(target, auth);

        JsonObject body = GSON.toJsonTree(Map.of("ids", ids)).getAsJsonObject();
        if (qqUpload != null) {
            body.add("qqUpload", GSON.toJsonTree(qqUpload));
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ENDPOINT + "/replays/renders/showcase/" + beatmapId))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        return getReplayTaskInfo(request);
    }

    public static ReplayRenderResult waitReplayVideo(String taskId) {
        FileInfo qqFile = waitReplayDone(taskId);
        return new ReplayRenderResult(
                ENDPOINT + "/replays/" + taskId + "/video/replay.mp4", taskId, qqFile);
    }

    private static ReplayTaskInfo createReplayTask(ShortcutTarget target,
                                                    TimeDurationParser.TimeRange timeRange,
                                                    QqUploadRequest qqUpload) {
        String scoreId = lookupScoreId(target);

        if (timeRange == null) {
            timeRange = getScoreHighlight(scoreId, 5);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ENDPOINT + "/replays/renders/score/" + scoreId + "?" + timeRange.toQueryString()))
                .header("Content-Type", "application/json")
                .POST(renderRequestBody(qqUpload))
                .build();

        return getReplayTaskInfo(request);
    }

    private static TimeDurationParser.TimeRange getScoreHighlight(long scoreId, int extend) {
        return getScoreHighlight(String.valueOf(scoreId), extend);
    }

    private static TimeDurationParser.TimeRange getScoreHighlight(String scoreId, int extend) {
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
                    Math.max(0, (int) (data.get("start").getAsLong() / 1000)) - extend,
                    (int) (data.get("end").getAsLong() / 1000) + extend
            );
        } catch (IOException | InterruptedException e) {
            throw requestFailure(e);
        }
    }

    private static String lookupScoreId(ShortcutTarget target) {
        String scoreId;
        if (target.isLocalScore()) {
            scoreId = target.localScoreId();
        } else if (!target.isMacro()) {
            scoreId = String.valueOf(target.explicitId());
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

                scoreId = rawResponse.getData().getAsJsonObject().get("score_id").getAsString();
            } catch (IOException | InterruptedException e) {
                throw requestFailure(e);
            }
        }
        return scoreId;
    }

    private static JsonObject lookupScoreData(String scoreId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT + "/scores/lookup?s=" + scoreId))
                    .GET()
                    .build();
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw parseHttpError(response.body(), response.statusCode(), "获取本地成绩信息失败");
            }
            RawResponse payload = GSON.fromJson(response.body(), RawResponse.class);
            ensureApiSuccess(payload, "获取本地成绩信息失败");
            return requireDataObject(payload, "本地成绩响应缺少data");
        } catch (IOException | InterruptedException e) {
            throw requestFailure(e);
        }
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
            String mods = data.has("mods") ? data.get("mods").getAsString() : null;
            String selection = data.has("selection") ? data.get("selection").getAsString() : null;

            return new ReplayTaskInfo(
                    taskId, status, position, beatmap, data.getAsJsonArray("scores"), start, end, mods, selection);
        } catch (IOException e) {
            throw requestFailure(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Replay render request interrupted", e);
        }
    }

    private static FileInfo waitReplayDone(String taskId) {
        for (int attempt = 1; attempt <= REPLAY_MAX_POLL_ATTEMPTS; attempt++) {
            JsonObject statusData = getReplayStatus(taskId);
            String status = statusData.get("status").getAsString();
            if ("done".equalsIgnoreCase(status)) {
                return statusData.has("qqFile") && statusData.get("qqFile").isJsonObject()
                        ? GSON.fromJson(statusData.getAsJsonObject("qqFile"), FileInfo.class)
                        : null;
            }
            if ("failed".equalsIgnoreCase(status)
                    || "timeout".equalsIgnoreCase(status)
                    || "canceled".equalsIgnoreCase(status)) {
                String error = statusData.has("error") && !statusData.get("error").isJsonNull()
                        ? statusData.get("error").getAsString()
                        : null;
                throw new ReplayRenderException(status, error);
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

    private static JsonObject getReplayStatus(String taskId) {
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
            return data;
        } catch (IOException e) {
            throw requestFailure(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Replay status request interrupted", e);
        }
    }

    private static HttpRequest.BodyPublisher renderRequestBody(QqUploadRequest qqUpload) {
        if (qqUpload == null) {
            return HttpRequest.BodyPublishers.noBody();
        }
        JsonObject body = new JsonObject();
        body.add("qqUpload", GSON.toJsonTree(qqUpload));
        return HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8);
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
            throw requestFailure(e);
        }
    }

    public static ServerStatus getServerStatus() {
        boolean oStella = false;
        boolean osu = false;
        String oStellaVersion = null;
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

                final JsonElement rawResponseData = rawResponse.getData();
                if (rawResponseData != null && rawResponseData.isJsonObject()) {
                    JsonObject data = rawResponseData.getAsJsonObject();

                    oStellaVersion = data.get("ostella_version").getAsString();
                    if (data.has("osu_api") && !data.get("osu_api").isJsonNull()) {
                        osu = data.get("osu_api").getAsBoolean();
                    }
                }
            }
        } catch (Exception _) {
        }

        return new ServerStatus(true, oStella, oStellaVersion, osu);
    }

    public static Response<List<MissData>> getScoreMissesResponse(ShortcutTarget target) {
        final String scoreId = lookupScoreId(target);
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
            throw requestFailure(e);
        }
    }

    public static long resolveUid(UserRef userRef) {
        if (userRef instanceof UserRef.ByUid byUid) {
            return byUid.getUid();
        }
        if (userRef instanceof UserRef.ByUsername byUsername) {
            return lookupUser(byUsername.getUsername()).getContent().getId();
        }
        throw new ResolutionException("无法识别指定的玩家");
    }

    public static Response<User> lookupUser(String username) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT + "/users/lookup"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            GSON.toJsonTree(Map.of("user_name", username)).toString()
                    ))
                    .build();

            final HttpResponse<String> send = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (send.statusCode() != 200) {
                throw parseHttpError(send.body(), send.statusCode(), "查找玩家失败");
            }

            final RawResponse response = GSON.fromJson(send.body(), RawResponse.class);
            ensureApiSuccess(response, "查找玩家失败");
            final JsonObject data = requireDataObject(response, "查找玩家响应缺少用户数据");

            return Response.<User>fromHeaders(send.headers())
                    .content(GSON.fromJson(data, User.class))
                    .build();
        } catch (IOException e) {
            throw requestFailure(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("查找玩家请求被中断", e);
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
            throw requestFailure(e);
        }
    }

    public static ReplayUploadInfo uploadReplay(byte[] replayBytes) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT + "/replays/upload"))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(replayBytes))
                    .build();

            final HttpResponse<String> send = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (send.statusCode() != 200 && send.statusCode() != 404) {
                throw parseHttpError(send.body(), send.statusCode(), "回放上传失败");
            }

            final RawResponse r = GSON.fromJson(send.body(), RawResponse.class);

            if (send.statusCode() == 404) {
                final Integer errCode = extractErrorCode(r);
                if (errCode != null && errCode == ErrorCode.NO_SCORE_FOUND.getCode()) {
                    throw new ApiRequestException(ErrorCode.NO_SCORE_FOUND.getCode(), "回放上传失败：无法获取对应的成绩");
                }
            }

            ensureApiSuccess(r, "回放上传失败");

            return GSON.fromJson(r.getData().getAsJsonObject(), ReplayUploadInfo.class);
        } catch (IOException | InterruptedException e) {
            throw requestFailure(e);
        }
    }

    private static RuntimeException requestFailure(Exception exception) {
        if (exception instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        return new RuntimeException(exception);
    }

    public record ServerStatus(boolean gateway, boolean oStella, String oStellaVersion, boolean osu) {
    }

    public record ReplayRenderResult(String videoUrl, String taskId, FileInfo qqFile) {
        public ReplayRenderResult(String videoUrl, String taskId) {
            this(videoUrl, taskId, null);
        }
    }

    public record ReplayTaskInfo(
            String taskId,
            String status,
            Integer position,
            BeatmapExtended beatmap,
            JsonArray scores,
            Double start,
            Double end,
            String mods,
            String selection
    ) {
    }
}
