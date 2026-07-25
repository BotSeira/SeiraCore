package xyz.zcraft.seira.command;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.seira.bot.data.Attachment;
import xyz.zcraft.seira.config.AppConfig;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class AttachmentHandler {
    private static final Logger LOG = LogManager.getLogger(AttachmentHandler.class);
    public final AppConfig config;
    private final Executor filePullExecutor = Executors.newFixedThreadPool(4);

    public AttachmentHandler(AppConfig config) {
        this.config = config;
    }

    public void handleAttachments(List<Attachment> attachments, Consumer<String> msgSender) {
        attachments.stream()
                .filter(attachment -> "file".equals(attachment.contentType()))
                .filter(attachment -> attachment.filename().endsWith(".osr"))
                .forEach(a -> handleAttachment(a, msgSender));
    }

    public void handleAttachment(Attachment attachment, Consumer<String> msgSender) {
        if (attachment.size() > 128 * 1024) {
            msgSender.accept(attachment.filename() + " 文件过大，无法上传!");
            return;
        }
        filePullExecutor.execute(() -> {
            try {
                final Path path = Path.of(config.seira().replayPath());
                Files.createDirectories(path);

                final InputStream inputStream = URI.create(attachment.url()).toURL().openStream();
                Files.copy(inputStream, path.resolve(attachment.filename()));

                msgSender.accept(attachment.filename() + " 上传成功~");
            } catch (IOException e) {
                LOG.error("Error occurred while downloading attachment: {}", attachment.filename(), e);
                msgSender.accept(attachment.filename() + " 上传失败:" + e.getMessage());
            }
        });
    }
}
