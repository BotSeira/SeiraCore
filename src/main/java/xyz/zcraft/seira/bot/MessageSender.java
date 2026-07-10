package xyz.zcraft.seira.bot;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.seira.bot.data.FileInfo;
import xyz.zcraft.seira.bot.data.Message;
import xyz.zcraft.seira.util.CosService;
import xyz.zcraft.seira.util.TokenManager;

public class MessageSender {
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

    public FileInfo uploadPrivateMedia(String userId, int fileType, String url, boolean uploadCos) {
        LOG.info("Uploading private media for user {}, fileType {}, url {}", userId, fileType, url);

        try {
            if (uploadCos) url = cos.uploadFromUrl(url, fileType);
        } catch (Exception e) {
            LOG.error("Failed to upload private media to COS");
            return null;
        }

        for (int i = 1; i <= 10; i++) {
            try {
                return QQApi.uploadPrivateMedia(tokenManager.getToken(), userId, fileType, url);
            } catch (RuntimeException e) {
                LOG.error("Failed to upload private media ({}/10) {}", i, userId, e);
            }

            try {
                Thread.sleep(i * 1000);
            } catch (Exception _) {
            }
        }

        return null;
    }

    public FileInfo uploadGroupMedia(String groupId, int fileType, String url, boolean uploadCos) {
        LOG.info("Uploading group media for group {}, fileType {}, url {}", groupId, fileType, url);

        try {
            if (uploadCos) url = cos.uploadFromUrl(url, fileType);
        } catch (Exception e) {
            LOG.error("Failed to upload group media to COS");
            return null;
        }

        for (int i = 1; i <= 10; i++) {
            try {
                return QQApi.uploadGroupMedia(tokenManager.getToken(), groupId, fileType, url);
            } catch (RuntimeException e) {
                LOG.error("Failed to upload group media ({}/10) {}", i, groupId, e);
            }

            try {
                Thread.sleep(i * 2000);
            } catch (Exception _) {
            }
        }

        return null;
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
}
