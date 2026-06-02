package xyz.zcraft.seira.util;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.region.Region;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.zcraft.seira.config.CosConfig;
import xyz.zcraft.seira.bot.data.PendingMessage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CosService {
    private static final Logger LOG = LogManager.getLogger(CosService.class);
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    private final ConcurrentHashMap<String, FileUpload> urlCache = new ConcurrentHashMap<>();

    private final CosConfig config;
    private final COSClient client;

    public CosService(CosConfig config) {
        this.config = config;
        COSCredentials credentials = new BasicCOSCredentials(config.secretId(), config.secretKey());
        ClientConfig clientConfig = new ClientConfig(new Region(config.region()));
        client = new COSClient(credentials, clientConfig);

        Runtime.getRuntime().addShutdownHook(new Thread(client::shutdown));
    }

    public String uploadFromUrl(String sourceUrl, int fileType) {
        return uploadFromUrl(sourceUrl, fileType, null);
    }

    public String uploadFromUrl(String sourceUrl, int fileType, String objectKey) {
        LOG.info("Processing file upload from url: {}", sourceUrl);

        DownloadedMedia media = downloadMedia(sourceUrl);
        if (objectKey == null) {
            objectKey = buildObjectKey(fileType, sourceUrl, media.contentType());
        }

        final String finalObjectKey = objectKey;

        urlCache.entrySet().removeIf(entry -> entry.getValue().uploadedAt() < System.currentTimeMillis() - 24 * 3600 * 1000);

        return urlCache.computeIfAbsent(media.digest(), _ -> {
            String url = doUpload(finalObjectKey, media);
            LOG.info("Uploaded media to COS. sourceUrl={}, cosUrl={}", sourceUrl, url);
            return new FileUpload(url, System.currentTimeMillis());
        }).url();
    }

    @NotNull
    private String doUpload(String objectKey, DownloadedMedia media) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(media.content().length);
        if (media.contentType() != null && !media.contentType().isBlank()) {
            metadata.setContentType(media.contentType());
        }

        PutObjectRequest putObjectRequest = new PutObjectRequest(
                config.bucket(),
                objectKey,
                new ByteArrayInputStream(media.content()),
                metadata
        );
        client.putObject(putObjectRequest);

        return buildObjectUrl(objectKey);
    }

    private DownloadedMedia downloadMedia(String sourceUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(sourceUrl))
                    .GET()
                    .build();

            HttpResponse<byte[]> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException("Failed to fetch media url: status=" + response.statusCode() + " url=" + sourceUrl);
            }

            String contentType = response.headers().firstValue("Content-Type").orElse(null);
            return DownloadedMedia.create(response.body(), contentType);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("Failed to fetch media url: " + sourceUrl, e);
        }
    }

    private String buildObjectKey(int fileType, String sourceUrl, String contentType) {
        String prefix = normalizePrefix(config.keyPrefix());
        String date = LocalDate.now().toString();
        String extension = detectExtension(fileType, sourceUrl, contentType);
        String filename = UUID.randomUUID().toString().replace("-", "") + extension;
        return prefix + date + "/" + filename;
    }

    private String detectExtension(int fileType, String sourceUrl, String contentType) {
        String fromUrl = extensionFromUrl(sourceUrl);
        if (fromUrl != null) {
            return fromUrl;
        }

        if (contentType != null && !contentType.isBlank()) {
            String lowered = contentType.toLowerCase(Locale.ROOT);
            if (lowered.contains("mp4")) {
                return ".mp4";
            }
            if (lowered.contains("webm")) {
                return ".webm";
            }
            if (lowered.contains("quicktime")) {
                return ".mov";
            }
            if (lowered.contains("jpeg")) {
                return ".jpg";
            }
            if (lowered.contains("png")) {
                return ".png";
            }
            if (lowered.contains("gif")) {
                return ".gif";
            }
        }

        return fileType == PendingMessage.FILE_TYPE_VIDEO ? ".mp4" : ".bin";
    }

    private String extensionFromUrl(String sourceUrl) {
        int queryIdx = sourceUrl.indexOf('?');
        String path = queryIdx >= 0 ? sourceUrl.substring(0, queryIdx) : sourceUrl;
        int slash = path.lastIndexOf('/');
        String filename = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = filename.lastIndexOf('.');
        if (dot <= 0 || dot == filename.length() - 1) {
            return null;
        }

        String ext = filename.substring(dot).toLowerCase(Locale.ROOT);
        if (ext.length() > 8) {
            return null;
        }
        return ext;
    }

    private String normalizePrefix(String keyPrefix) {
        String prefix = keyPrefix == null ? "seira" : keyPrefix.trim();
        if (prefix.isEmpty()) {
            prefix = "seira";
        }

        while (prefix.startsWith("/")) {
            prefix = prefix.substring(1);
        }
        while (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix + "/";
    }

    private String buildObjectUrl(String objectKey) {
        if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
            return trimTrailingSlash(config.baseUrl()) + "/" + objectKey;
        }
        return "https://" + config.bucket() + ".cos." + config.region() + ".myqcloud.com/" + objectKey;
    }

    private String trimTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private record DownloadedMedia(byte[] content, String contentType, String digest) {
        public static DownloadedMedia create(byte[] content, String contentType) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] digest = md.digest(content);

                StringBuilder hexString = new StringBuilder();
                for (byte b : digest) {
                    hexString.append(String.format("%02x", b));
                }

                return new DownloadedMedia(content, contentType, hexString.toString());
            } catch (NoSuchAlgorithmException e) {
                LOG.error("Failed to create media digest", e);
                return new DownloadedMedia(content, contentType, null);
            }
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof DownloadedMedia another
                    && digest != null && another.digest != null) {
                return Objects.equals(another.digest, digest);
            }

            return false;
        }
    }

    private record FileUpload(String url, long uploadedAt){}
}

