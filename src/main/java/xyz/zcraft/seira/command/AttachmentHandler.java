package xyz.zcraft.seira.command;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.seira.api.APIHelper;
import xyz.zcraft.seira.api.data.ReplayUploadInfo;
import xyz.zcraft.seira.bot.data.Attachment;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.reply.ReplyFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public class AttachmentHandler {
    private static final Logger LOG = LogManager.getLogger(AttachmentHandler.class);
    private static final int MAX_REPLAY_SIZE = 512 * 1024;
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(30);

    private final Executor filePullExecutor;
    private final HttpClient httpClient;

    public AttachmentHandler(Executor filePullExecutor) {
        this(filePullExecutor, HttpClient.newBuilder().connectTimeout(DOWNLOAD_TIMEOUT).build());
    }

    AttachmentHandler(Executor filePullExecutor, HttpClient httpClient) {
        this.filePullExecutor = java.util.Objects.requireNonNull(filePullExecutor);
        this.httpClient = java.util.Objects.requireNonNull(httpClient);
    }

    public void handleAttachments(List<Attachment> attachments, Consumer<PendingMessage> msgSender) {
        attachments.stream()
                .filter(a -> "file".equals(a.contentType()))
                .filter(a -> a.filename().toLowerCase().endsWith(".osr"))
                .forEach(a -> handleAttachment(a, msgSender));
    }

    public void handleAttachment(Attachment attachment, Consumer<PendingMessage> msgSender) {
        if (attachment.size() > MAX_REPLAY_SIZE) {
            msgSender.accept(PendingMessage.ofMarkdownRaw(attachment.filename() + " 文件过大，无法上传!"));
            return;
        }
        filePullExecutor.execute(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(attachment.url()))
                        .timeout(DOWNLOAD_TIMEOUT)
                        .GET()
                        .build();
                HttpResponse<java.io.InputStream> response = httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.ofInputStream()
                );
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    response.body().close();
                    throw new IllegalStateException("Replay download failed with HTTP " + response.statusCode());
                }

                final byte[] bytes;
                try (var inputStream = response.body()) {
                    bytes = inputStream.readNBytes(MAX_REPLAY_SIZE + 1);
                }
                if (bytes.length > MAX_REPLAY_SIZE) {
                    throw new IllegalArgumentException("Replay file exceeds the 128 KiB limit");
                }

                final ReplayUploadInfo replayUploadInfo = APIHelper.uploadReplay(bytes);

                msgSender.accept(ReplyFactory.replayUploadMessage(replayUploadInfo));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.warn("Replay upload interrupted: {}", attachment.filename());
            } catch (Exception e) {
                LOG.error("Error occurred while uploading replay: {}", attachment.filename(), e);
                msgSender.accept(PendingMessage.ofMarkdownRaw(attachment.filename() + " 上传失败:\n > " + e.getMessage()));
            }
        });
    }
}
