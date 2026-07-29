package xyz.zcraft.seira.command;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.seira.api.APIHelper;
import xyz.zcraft.seira.api.data.ReplayUploadInfo;
import xyz.zcraft.seira.bot.data.Attachment;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.config.AppConfig;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
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

    public void handleAttachments(List<Attachment> attachments, Consumer<PendingMessage> msgSender) {
        attachments.stream()
                .filter(attachment -> "file".equals(attachment.contentType()))
                .filter(attachment -> attachment.filename().endsWith(".osr"))
                .forEach(a -> handleAttachment(a, msgSender));
    }

    public void handleAttachment(Attachment attachment, Consumer<PendingMessage> msgSender) {
        if (attachment.size() > 128 * 1024) {
            msgSender.accept(PendingMessage.ofMarkdownRaw(attachment.filename() + " 文件过大，无法上传!"));
            return;
        }
        filePullExecutor.execute(() -> {
            try {
                final InputStream inputStream = URI.create(attachment.url()).toURL().openStream();

                final byte[] bytes = inputStream.readAllBytes();

                inputStream.close();

                final ReplayUploadInfo replayUploadInfo = APIHelper.uploadReplay(bytes);

                msgSender.accept(ReplyFactory.replayUploadMessage(replayUploadInfo));
            } catch (IOException e) {
                LOG.error("Error occurred while uploading replay: {}", attachment.filename(), e);
                msgSender.accept(PendingMessage.ofMarkdownRaw(attachment.filename() + " 上传失败:" + e.getMessage()));
            }
        });
    }
}
