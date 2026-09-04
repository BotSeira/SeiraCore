package xyz.zcraft.seira.bot;

import com.google.gson.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.seira.bot.data.*;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public class QQApi {
    private static final String ENDPOINT = "https://api.sgroup.qq.com";
    private static final HttpClient CLIENT = HttpClient.newBuilder().build();
    private static final HttpClient MEDIA_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    private static final long MD5_10M_SIZE = 10_002_432L;
    private static final long MAX_MEDIA_SIZE = 200L * 1024 * 1024;
    private static final Gson gson = new Gson();
    private static final Logger LOG = LogManager.getLogger(QQApi.class);
    private static final Gson GSON = new Gson();

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
            throw requestFailure(e);
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

    public static SentMessage sendPrivateMessage(AccessToken accessToken, String openId, Message message) {
        try {
            final var request = newRequestBuilder(accessToken)
                    .uri(URI.create(ENDPOINT + "/v2/users/" + openId + "/messages"))
                    .POST(HttpRequest.BodyPublishers.ofString(buildMessageJson(message)))
                    .build();

            final HttpResponse<String> send = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (send.statusCode() != 200) {
                throw new RuntimeException("Failed to send private message to " + openId + " " + send.body());
            }

            return GSON.fromJson(send.body(), SentMessage.class);
        } catch (IOException | InterruptedException e) {
            throw requestFailure(e);
        }
    }

    public static SentMessage sendGroupMessage(AccessToken accessToken, String groupId, Message message) {
        try {
            final var request = newRequestBuilder(accessToken)
                    .uri(URI.create(ENDPOINT + "/v2/groups/" + groupId + "/messages"))
                    .POST(HttpRequest.BodyPublishers.ofString(buildMessageJson(message)))
                    .build();

            final HttpResponse<String> send = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (send.statusCode() != 200) {
                throw new RuntimeException("Failed to send group message to " + groupId + " " + send.body());
            }

            return GSON.fromJson(send.body(), SentMessage.class);
        } catch (IOException | InterruptedException e) {
            throw requestFailure(e);
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
            throw requestFailure(e);
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
            throw requestFailure(e);
        }
    }

    public static FileInfo uploadPrivateVideoByParts(AccessToken accessToken, String openId, String sourceUrl) {
        return uploadVideoByParts(accessToken, "users", openId, sourceUrl);
    }

    public static FileInfo uploadGroupVideoByParts(AccessToken accessToken, String openId, String sourceUrl) {
        return uploadVideoByParts(accessToken, "groups", openId, sourceUrl);
    }

    private static FileInfo uploadVideoByParts(AccessToken accessToken, String targetType, String openId, String sourceUrl) {
        Path video = null;
        try {
            video = downloadVideo(sourceUrl);
            long fileSize = Files.size(video);
            if (fileSize == 0 || fileSize > MAX_MEDIA_SIZE) {
                throw new IllegalArgumentException("Video size must be between 1 byte and 200 MB: " + fileSize);
            }

            String fileName = videoFileName(sourceUrl);
            MediaDigests digests = calculateDigests(video);
            UploadPrepare prepare = prepareUpload(accessToken, targetType, openId, fileName, fileSize, digests);
            uploadParts(accessToken, targetType, openId, video, prepare);
            return completeUpload(accessToken, targetType, openId, fileName, prepare.uploadId());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("Failed to upload video directly to QQ", e);
        } finally {
            if (video != null) {
                try {
                    Files.deleteIfExists(video);
                } catch (IOException e) {
                    LOG.warn("Failed to delete temporary video {}", video, e);
                }
            }
        }
    }

    private static Path downloadVideo(String sourceUrl) throws IOException, InterruptedException {
        Path target = Files.createTempFile("seira-qq-video-", ".mp4");
        boolean success = false;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(sourceUrl))
                    .timeout(Duration.ofMinutes(10))
                    .GET()
                    .build();
            HttpResponse<Path> response = MEDIA_CLIENT.send(request, HttpResponse.BodyHandlers.ofFile(target));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Failed to download video: status=" + response.statusCode());
            }
            success = true;
            return target;
        } finally {
            if (!success) {
                Files.deleteIfExists(target);
            }
        }
    }

    private static MediaDigests calculateDigests(Path file) throws IOException {
        MessageDigest md5 = newDigest("MD5");
        MessageDigest sha1 = newDigest("SHA-1");
        MessageDigest md5First10m = newDigest("MD5");
        long hashedFor10m = 0;

        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                md5.update(buffer, 0, read);
                sha1.update(buffer, 0, read);
                if (hashedFor10m < MD5_10M_SIZE) {
                    int digestLength = (int) Math.min(read, MD5_10M_SIZE - hashedFor10m);
                    md5First10m.update(buffer, 0, digestLength);
                    hashedFor10m += digestLength;
                }
            }
        }

        return new MediaDigests(
                HexFormat.of().formatHex(md5.digest()),
                HexFormat.of().formatHex(sha1.digest()),
                HexFormat.of().formatHex(md5First10m.digest())
        );
    }

    private static MessageDigest newDigest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Missing digest algorithm " + algorithm, e);
        }
    }

    private static UploadPrepare prepareUpload(AccessToken accessToken, String targetType, String openId,
                                               String fileName, long fileSize, MediaDigests digests)
            throws IOException, InterruptedException {
        JsonObject payload = new JsonObject();
        payload.addProperty("file_type", 2);
        payload.addProperty("file_size", Long.toString(fileSize));
        payload.addProperty("file_name", fileName);
        payload.addProperty("md5", digests.md5());
        payload.addProperty("sha1", digests.sha1());
        payload.addProperty("md5_10m", digests.md5First10m());

        HttpResponse<String> response = sendJson(
                accessToken,
                "/v2/" + targetType + "/" + openId + "/upload_prepare",
                payload
        );
        JsonObject data = parseResponseData(response, "prepare video upload");
        String uploadId = requiredString(data, "upload_id", "prepare video upload");
        long blockSize = data.has("block_size") ? data.get("block_size").getAsLong() : 5L * 1024 * 1024;

        List<UploadPart> parts = new ArrayList<>();
        if (data.has("parts") && data.get("parts").isJsonArray()) {
            data.getAsJsonArray("parts").forEach(element -> {
                JsonObject part = element.getAsJsonObject();
                parts.add(new UploadPart(
                        part.get("index").getAsInt(),
                        requiredString(part, "presigned_url", "prepare video upload"),
                        part.has("block_size") ? part.get("block_size").getAsLong() : blockSize
                ));
            });
        }
        parts.sort(Comparator.comparingInt(UploadPart::index));

        JsonObject config = data.has("upload_config") && data.get("upload_config").isJsonObject()
                ? data.getAsJsonObject("upload_config")
                : new JsonObject();
        int concurrency = config.has("concurrency") ? config.get("concurrency").getAsInt() : 1;
        int retryTimeout = config.has("retry_timeout") ? config.get("retry_timeout").getAsInt() : 300;
        int retryDelay = config.has("retry_delay") ? config.get("retry_delay").getAsInt() : 1;
        return new UploadPrepare(uploadId, blockSize, parts, Math.max(1, concurrency),
                Math.max(1, retryTimeout), Math.max(1, retryDelay));
    }

    private static void uploadParts(AccessToken accessToken, String targetType, String openId,
                                    Path file, UploadPrepare prepare) throws IOException, InterruptedException {
        if (prepare.parts().isEmpty()) {
            return;
        }

        int workerCount = Math.min(prepare.concurrency(), prepare.parts().size());
        try (ExecutorService executor = Executors.newFixedThreadPool(workerCount)) {
            List<Callable<Void>> tasks = prepare.parts().stream()
                    .<Callable<Void>>map(part -> () -> {
                        uploadPartWithRetry(accessToken, targetType, openId, file, prepare, part);
                        return null;
                    })
                    .toList();
            List<Future<Void>> futures = executor.invokeAll(tasks);
            for (Future<Void> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    throw new IOException("Failed to upload a video part", e.getCause());
                }
            }
        }
    }

    private static void uploadPartWithRetry(AccessToken accessToken, String targetType, String openId,
                                            Path file, UploadPrepare prepare, UploadPart part) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(prepare.retryTimeoutSeconds());
        Exception lastFailure;
        do {
            try {
                byte[] content = readPart(file, prepare.blockSize(), part);
                HttpRequest putRequest = HttpRequest.newBuilder()
                        .uri(URI.create(part.presignedUrl()))
                        .timeout(Duration.ofMinutes(5))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
                        .build();
                HttpResponse<String> putResponse = MEDIA_CLIENT.send(putRequest, HttpResponse.BodyHandlers.ofString());
                if (putResponse.statusCode() < 200 || putResponse.statusCode() >= 300) {
                    throw new IOException("Part PUT failed: index=" + part.index() + " status=" + putResponse.statusCode());
                }

                finishPart(accessToken, targetType, openId, prepare.uploadId(), part.index(), content);
                return;
            } catch (IOException | RuntimeException e) {
                lastFailure = e;
                if (System.nanoTime() >= deadline) {
                    break;
                }
                Thread.sleep(TimeUnit.SECONDS.toMillis(prepare.retryDelaySeconds()));
            }
        } while (System.nanoTime() < deadline);
        throw new IOException("Video part upload retry timeout: index=" + part.index(), lastFailure);
    }

    private static byte[] readPart(Path file, long defaultBlockSize, UploadPart part) throws IOException {
        long offset = Math.multiplyExact((long) part.index() - 1, defaultBlockSize);
        long fileSize = Files.size(file);
        long requestedSize = part.blockSize() > 0 ? part.blockSize() : defaultBlockSize;
        int size = Math.toIntExact(Math.min(requestedSize, fileSize - offset));
        if (size <= 0) {
            throw new IOException("Invalid video part range: index=" + part.index());
        }

        ByteBuffer buffer = ByteBuffer.allocate(size);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer, offset + buffer.position());
                if (read < 0) {
                    throw new IOException("Unexpected end of video while reading part " + part.index());
                }
            }
        }
        return buffer.array();
    }

    private static void finishPart(AccessToken accessToken, String targetType, String openId,
                                   String uploadId, int partIndex, byte[] content)
            throws IOException, InterruptedException {
        JsonObject payload = new JsonObject();
        payload.addProperty("upload_id", uploadId);
        payload.addProperty("part_index", partIndex);
        payload.addProperty("block_size", Integer.toString(content.length));
        payload.addProperty("md5", HexFormat.of().formatHex(newDigest("MD5").digest(content)));
        HttpResponse<String> response = sendJson(
                accessToken,
                "/v2/" + targetType + "/" + openId + "/upload_part_finish",
                payload
        );
        parseResponseData(response, "finish video part " + partIndex);
    }

    private static FileInfo completeUpload(AccessToken accessToken, String targetType, String openId,
                                           String fileName, String uploadId)
            throws IOException, InterruptedException {
        JsonObject payload = new JsonObject();
        payload.addProperty("file_type", 2);
        payload.addProperty("srv_send_msg", false);
        payload.addProperty("file_name", fileName);
        payload.addProperty("upload_id", uploadId);
        HttpResponse<String> response = sendJson(
                accessToken,
                "/v2/" + targetType + "/" + openId + "/files",
                payload
        );
        return parseUploadedFileInfo(response, "complete video upload");
    }

    private static HttpResponse<String> sendJson(AccessToken accessToken, String path, JsonObject payload)
            throws IOException, InterruptedException {
        HttpRequest request = newRequestBuilder(accessToken)
                .uri(URI.create(ENDPOINT + path))
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static JsonObject parseResponseData(HttpResponse<String> response, String action) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("Failed to " + action + "! Status code: " + response.statusCode()
                    + " body=" + response.body());
        }
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        return root.has("data") && root.get("data").isJsonObject() ? root.getAsJsonObject("data") : root;
    }

    private static String requiredString(JsonObject object, String name, String action) {
        if (!object.has(name) || object.get(name).isJsonNull() || object.get(name).getAsString().isBlank()) {
            throw new RuntimeException("Failed to " + action + ": missing " + name);
        }
        return object.get(name).getAsString();
    }

    private static String videoFileName(String sourceUrl) {
        String path = URI.create(sourceUrl).getPath();
        String name = path == null ? "" : path.substring(path.lastIndexOf('/') + 1);
        if (name.isBlank() || !name.toLowerCase(Locale.ROOT).endsWith(".mp4")) {
            return "video.mp4";
        }
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
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
            throw requestFailure(e);
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
            throw requestFailure(e);
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

    public static QQUser getSelf(AccessToken accessToken) {
        try {
            final var request = newRequestBuilder(accessToken)
                    .uri(URI.create(ENDPOINT + "/users/@me"))
                    .GET()
                    .build();

            final HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            return gson.fromJson(parseResponseData(response, "get self info"), QQUser.class);
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

    public static List<PanelRecord> listPanels(AccessToken accessToken, String scope) {
        try {
            final var request = newRequestBuilder(accessToken)
                    .uri(URI.create(ENDPOINT + "/v2/panels" + "?scope=" + scope + "&limit=50"))
                    .GET()
                    .build();

            final HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            final JsonObject rootObj = JsonParser.parseString(response.body()).getAsJsonObject();

            final JsonArray recordsArr = rootObj.getAsJsonArray("records");

            if (recordsArr == null || recordsArr.isEmpty()) {
                return List.of();
            }

            final LinkedList<PanelRecord> records = new LinkedList<>();

            for (JsonElement jsonElement : recordsArr) {
                records.add(gson.fromJson(jsonElement, PanelRecord.class));
            }

            return records;
        } catch (IOException | InterruptedException e) {
            throw requestFailure(e);
        }
    }

    public static PanelRecord getPanel(AccessToken accessToken, String panelId) {
        try {
            final var request = newRequestBuilder(accessToken)
                    .uri(URI.create(ENDPOINT + "/v2/panels/" + panelId))
                    .GET()
                    .build();

            final HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOG.error("Failed to get panel, status code: {} body={}", response.statusCode(), response.body());
                throw new RuntimeException("Failed to get panel, status code: " + response.statusCode() + " body=" + response.body());
            }

            return gson.fromJson(parseResponseData(response, "get panel"), PanelRecord.class);
        } catch (IOException | InterruptedException e) {
            throw requestFailure(e);
        }
    }

    public static String createPanel(AccessToken accessToken, String scope, Panel panel) {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("scope", scope);
            body.addProperty("target_type", "all");
            body.add("panel", gson.toJsonTree(panel));

            final var request = newRequestBuilder(accessToken)
                    .uri(URI.create(ENDPOINT + "/v2/panels"))
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            final HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOG.error("Failed to create panel, status code: {} body={}", response.statusCode(), response.body());
                throw new RuntimeException("Failed to create panel, status code: " + response.statusCode() + " body=" + response.body());
            }

            return JsonParser.parseString(response.body()).getAsJsonObject().get("panel_id").getAsString();
        } catch (IOException | InterruptedException e) {
            throw requestFailure(e);
        }
    }

    public static void deletePanel(AccessToken accessToken, String panelId) {
        try {
            final var request = newRequestBuilder(accessToken)
                    .uri(URI.create(ENDPOINT + "/v2/panels/" + panelId))
                    .DELETE()
                    .build();

            final HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOG.error("Failed to delete panel, status code: {} body={}", response.statusCode(), response.body());
                throw new RuntimeException("Failed to delete panel with ID: " + panelId);
            }
        } catch (IOException | InterruptedException e) {
            throw requestFailure(e);
        }
    }

    public static int editPanel(AccessToken accessToken, String panelId, Panel newPanel) {
        try {
            JsonObject body = new JsonObject();
            body.add("panel", gson.toJsonTree(newPanel));

            final var request = newRequestBuilder(accessToken)
                    .uri(URI.create(ENDPOINT + "/v2/panels/" + panelId))
                    .PUT(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            final HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOG.error("Failed to edit panel, status code: {} body={}", response.statusCode(), response.body());
                throw new RuntimeException("Failed to edit panel, status code: " + response.statusCode() + " body=" + response.body());
            }

            return JsonParser.parseString(response.body()).getAsJsonObject().get("version").getAsInt();
        } catch (IOException | InterruptedException e) {
            throw requestFailure(e);
        }
    }

    private record MediaDigests(String md5, String sha1, String md5First10m) {
    }

    private record UploadPart(int index, String presignedUrl, long blockSize) {
    }

    private record UploadPrepare(String uploadId, long blockSize, List<UploadPart> parts, int concurrency,
                                 int retryTimeoutSeconds, int retryDelaySeconds) {
    }
}
