package xyz.zcraft.seira.agent;

import xyz.zcraft.seira.bot.data.Attachment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

final class ImageCache {
    private static final Path ROOT = Path.of("data", "agent-images").toAbsolutePath();
    private static final int MAX_IMAGE = 8 * 1024 * 1024;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    static Attachment capture(Attachment attachment) throws Exception {
        String url = attachment.url();
        if (url.startsWith("//")) url = "https:" + url;
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build();
        byte[] bytes;
        var response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var body = response.body()) {
            CompletableFuture.delayedExecutor(20, TimeUnit.SECONDS).execute(() -> {
                try {
                    body.close();
                } catch (Exception ignored) {
                }
            });
            if (response.statusCode() != 200) throw new java.io.IOException("Image download failed");
            bytes = body.readNBytes(MAX_IMAGE + 1);
            if (bytes.length == 0 || bytes.length > MAX_IMAGE)
                throw new java.io.IOException("Image size exceeds limit");
        }
        Path path = ROOT.resolve(UUID.randomUUID() + ".image");
        synchronized (ImageCache.class) {
            Files.createDirectories(ROOT);
            prune();
            Files.write(path, bytes);
        }
        return new Attachment(attachment.contentType(), attachment.filename(), bytes.length, path.toUri().toString());
    }

    private static void prune() throws Exception {
        try (var paths = Files.list(ROOT)) {
            var files = paths.filter(p -> p.getFileName().toString().endsWith(".image"))
                    .sorted(Comparator.comparingLong(p -> p.toFile().lastModified())).toList();
            long total = 0;
            for (Path path : files) total += Files.size(path);
            for (Path path : files) {
                if (System.currentTimeMillis() - Files.getLastModifiedTime(path).toMillis() > 86_400_000L
                        || total > 128L * 1024 * 1024 - MAX_IMAGE) {
                    total -= Files.size(path);
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    static String dataUrl(Attachment attachment) {
        try {
            Path path = Path.of(URI.create(attachment.url())).toAbsolutePath().normalize();
            if (!path.getParent().equals(ROOT) || !Files.exists(path)) return null;
            if (System.currentTimeMillis() - Files.getLastModifiedTime(path).toMillis() > 86_400_000L) return null;
            return "data:" + attachment.contentType().split(";", 2)[0] + ";base64,"
                    + Base64.getEncoder().encodeToString(Files.readAllBytes(path));
        } catch (Exception ignored) {
            return null;
        }
    }
}
