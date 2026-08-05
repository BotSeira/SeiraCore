package xyz.zcraft.seira.bot;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.seira.bot.data.FileInfo;
import xyz.zcraft.seira.bot.data.Message;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.data.UploadedImage;
import xyz.zcraft.seira.services.CosService;
import xyz.zcraft.seira.util.TokenManager;

import java.util.function.Supplier;

public class MessageSender implements ProactiveMessenger {
    private static final int MAX_UPLOAD_ATTEMPTS = 10;
    private final Logger LOG = LogManager.getLogger(MessageSender.class);
    private final TokenManager tokenManager;
    private final CosService cos;

    protected MessageSender(TokenManager tokenManager, CosService cos) {
        this.tokenManager = tokenManager;
        this.cos = cos;
    }

    public boolean sendPrivateMessage(String userId, Message message) {
        try {
            QQApi.sendPrivateMessage(tokenManager.getToken(), userId, message);
            return true;
        } catch (RuntimeException e) {
            LOG.error("Failed to send message to private {}", userId, e);
            return false;
        }
    }

    public boolean sendGroupMessage(String groupId, Message message) {
        try {
            QQApi.sendGroupMessage(tokenManager.getToken(), groupId, message);
            return true;
        } catch (RuntimeException e) {
            LOG.error("Failed to send message to group {}", groupId, e);
            return false;
        }
    }

    public UploadedImage uploadImageToCos(byte[] imageBytes) {
        return cos.uploadImage(imageBytes);
    }

    public UploadedImage uploadImageToCos(String imageUrl) {
        return cos.uploadImage(imageUrl);
    }

    public FileInfo uploadPrivateMedia(String userId, int fileType, String url, boolean uploadCos) {
        LOG.info("Uploading private media for user {}, fileType {}, url {}", userId, fileType, url);

        if (uploadCos && fileType == PendingMessage.FILE_TYPE_VIDEO) {
            try {
                return QQApi.uploadPrivateVideoByParts(tokenManager.getToken(), userId, url);
            } catch (RuntimeException e) {
                LOG.error("Failed to upload private video directly to QQ {}", userId, e);
                return null;
            }
        }

        try {
            if (uploadCos) url = cos.uploadFromUrl(url, fileType);
        } catch (Exception e) {
            LOG.error("Failed to upload private media to COS", e);
            return null;
        }

        String uploadUrl = url;
        return retryUpload(
                () -> QQApi.uploadPrivateMedia(tokenManager.getToken(), userId, fileType, uploadUrl),
                1_000L,
                "private media for " + userId
        );
    }

    public FileInfo uploadGroupMedia(String groupId, int fileType, String url, boolean uploadCos) {
        LOG.info("Uploading group media for group {}, fileType {}, url {}", groupId, fileType, url);

        if (uploadCos && fileType == PendingMessage.FILE_TYPE_VIDEO) {
            try {
                return QQApi.uploadGroupVideoByParts(tokenManager.getToken(), groupId, url);
            } catch (RuntimeException e) {
                LOG.error("Failed to upload group video directly to QQ {}", groupId, e);
                return null;
            }
        }

        try {
            if (uploadCos) url = cos.uploadFromUrl(url, fileType);
        } catch (Exception e) {
            LOG.error("Failed to upload group media to COS", e);
            return null;
        }

        String uploadUrl = url;
        return retryUpload(
                () -> QQApi.uploadGroupMedia(tokenManager.getToken(), groupId, fileType, uploadUrl),
                2_000L,
                "group media for " + groupId
        );
    }

    public FileInfo uploadPrivateMediaBase64(String userId, int fileType, String base64Str) {
        try {
            return QQApi.uploadPrivateMediaBase64(tokenManager.getToken(), userId, fileType, base64Str);
        } catch (RuntimeException e) {
            LOG.error("Failed to upload private media {}", userId, e);
            return null;
        }
    }

    public FileInfo uploadGroupMediaBase64(String groupId, int fileType, String base64Str) {
        try {
            return QQApi.uploadGroupMediaBase64(tokenManager.getToken(), groupId, fileType, base64Str);
        } catch (RuntimeException e) {
            LOG.error("Failed to upload group media {}", groupId, e);
            return null;
        }
    }

    @Override
    public boolean sendPrivateText(String userId, String content) {
        Message message = new Message();
        message.setMsgType(PendingMessage.MSG_TYPE_TEXT);
        message.setContent(content);
        return sendPrivateMessage(userId, message);
    }

    @Override
    public boolean sendGroupText(String groupId, String content) {
        Message message = new Message();
        message.setMsgType(PendingMessage.MSG_TYPE_TEXT);
        message.setContent(content);
        return sendGroupMessage(groupId, message);
    }

    private FileInfo retryUpload(Supplier<FileInfo> operation, long baseDelayMillis, String description) {
        for (int attempt = 1; attempt <= MAX_UPLOAD_ATTEMPTS; attempt++) {
            try {
                return operation.get();
            } catch (RuntimeException e) {
                LOG.error("Failed to upload {} ({}/{})", description, attempt, MAX_UPLOAD_ATTEMPTS, e);
            }

            if (attempt == MAX_UPLOAD_ATTEMPTS) {
                break;
            }
            try {
                Thread.sleep(Math.multiplyExact(baseDelayMillis, attempt));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.warn("Interrupted while retrying upload of {}", description);
                return null;
            }
        }
        return null;
    }
}
