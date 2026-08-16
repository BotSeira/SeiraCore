package xyz.zcraft.seira.discord;

import xyz.zcraft.seira.config.DiscordProxyConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

final class MediaDownloader {
    private final long maxBytes;
    private final HttpClient client;

    MediaDownloader(long maxBytes, DiscordProxyConfig proxy) {
        this.maxBytes = maxBytes;
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (proxy.enabled()) {
            builder.proxy(ProxySelector.of(InetSocketAddress.createUnresolved(proxy.host(), proxy.port())));
            if (!proxy.username().isBlank()) {
                builder.authenticator(new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(proxy.username(), proxy.password().toCharArray());
                    }
                });
            }
        }
        this.client = builder.build();
    }

    DownloadedMedia download(String url, String suggestedFilename) throws IOException, InterruptedException {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid media URL", e);
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("Unsupported media URL scheme: " + uri.getScheme());
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "SeiraCore-DiscordBridge/1.0")
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            response.body().close();
            throw new IOException("Media server returned HTTP " + response.statusCode());
        }
        long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        if (declaredLength > maxBytes) {
            response.body().close();
            throw new IOException("Media exceeds configured limit of " + maxBytes + " bytes");
        }

        byte[] data;
        try (InputStream input = response.body(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int total = 0;
            for (int read; (read = input.read(buffer)) >= 0; ) {
                total += read;
                if (total > maxBytes) {
                    throw new IOException("Media exceeds configured limit of " + maxBytes + " bytes");
                }
                output.write(buffer, 0, read);
            }
            data = output.toByteArray();
        }
        String declaredType = response.headers().firstValue("Content-Type")
                .map(value -> value.split(";", 2)[0].trim())
                .orElse("application/octet-stream");
        String contentType = MediaFormat.normalizeContentType(data, declaredType);
        String filename = safeFilename(suggestedFilename, uri, contentType);
        return new DownloadedMedia(data, MediaFormat.normalizeFilename(filename, contentType), contentType, url);
    }

    private static String safeFilename(String suggested, URI uri, String contentType) {
        String filename = suggested;
        if (filename == null || filename.isBlank() || filename.startsWith("http://") || filename.startsWith("https://")) {
            String path = uri.getPath();
            filename = path == null ? "" : path.substring(path.lastIndexOf('/') + 1);
        }
        filename = filename == null ? "" : filename.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        if (filename.isBlank() || filename.length() > 150) {
            filename = "media" + MediaFormat.extensionFor(contentType);
        }
        if (!filename.contains(".")) filename += MediaFormat.extensionFor(contentType);
        return filename;
    }
}
