package xyz.zcraft.bot;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.data.FileInfo;
import xyz.zcraft.data.Message;

public class MessageSender {
    private final Logger LOG = LogManager.getLogger(MessageSender.class);
    private final TokenManager tokenManager;
    private final CosService cos;

    protected MessageSender(TokenManager tokenManager, CosService cos) {
        this.tokenManager = tokenManager;
        this.cos = cos;
    }

    public void sendPrivateMessage(String userId, Message message) {
        try {
            QQApi.sendPrivateMessage(tokenManager.getToken(), userId, message);
        } catch (RuntimeException e) {
            LOG.error("Failed to send message to private {}", userId, e);
        }
    }

    public void sendGroupMessage(String groupId, Message message) {
        try {
            QQApi.sendGroupMessage(tokenManager.getToken(), groupId, message);
        } catch (RuntimeException e) {
            LOG.error("Failed to send message to group {}", groupId, e);
        }
    }

    public FileInfo uploadPrivateMedia(String userId, int fileType, String url) {
        try {
            LOG.info("Uploading private media for user {}, fileType {}, url {}", userId, fileType, url);
            if(cos != null) {
                url = cos.uploadFromUrl(url, fileType);
            }
            return QQApi.uploadPrivateMedia(tokenManager.getToken(), userId, fileType, url);
        } catch (RuntimeException e) {
            LOG.error("Failed to upload private media {}", userId, e);
            return null;
        }
    }

    public FileInfo uploadGroupMedia(String groupId, int fileType, String url) {
        try {
            LOG.info("Uploading group media for group {}, fileType {}, url {}", groupId, fileType, url);
            if(cos != null) {
                url = cos.uploadFromUrl(url, fileType);
            }
            return QQApi.uploadGroupMedia(tokenManager.getToken(), groupId, fileType, url);
        } catch (RuntimeException e) {
            LOG.error("Failed to upload group media {}", groupId, e);
            return null;
        }
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
