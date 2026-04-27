package xyz.zcraft.platform;

import org.apache.logging.log4j.Logger;
import xyz.zcraft.data.FileInfo;
import xyz.zcraft.data.Message;

public abstract class AbstractApiMessageSender implements PlatformMessageSender {
    private final PlatformPrivateMessageApi api;
    private final Logger log;

    protected AbstractApiMessageSender(PlatformPrivateMessageApi api, Logger log) {
        this.api = api;
        this.log = log;
    }

    @Override
    public void sendPrivateMessage(String userId, Message message) {
        try {
            api.sendPrivateMessage(userId, message);
        } catch (RuntimeException e) {
            log.error("Failed to send message to private {}", userId, e);
        }
    }

    @Override
    public void sendGroupMessage(String groupId, Message message) {
        try {
            api.sendGroupMessage(groupId, message);
        } catch (RuntimeException e) {
            log.error("Failed to send message to group {}", groupId, e);
        }
    }

    @Override
    public FileInfo uploadPrivateMedia(String userId, int fileType, String url) {
        try {
            log.info("Uploading private media for user {}, fileType {}, url {}", userId, fileType, url);
            return api.uploadPrivateMedia(userId, fileType, url);
        } catch (RuntimeException e) {
            log.error("Failed to upload private media {}", userId, e);
            return null;
        }
    }

    @Override
    public FileInfo uploadGroupMedia(String groupId, int fileType, String url) {
        try {
            return api.uploadGroupMedia(groupId, fileType, url);
        } catch (RuntimeException e) {
            log.error("Failed to upload group media {}", groupId, e);
            return null;
        }
    }

    @Override
    public FileInfo uploadPrivateMediaBase64(String userId, int fileType, String base64Str) {
        try {
            return api.uploadPrivateMediaBase64(userId, fileType, base64Str);
        } catch (RuntimeException e) {
            log.error("Failed to upload private media {}", userId, e);
            return null;
        }
    }

    @Override
    public FileInfo uploadGroupMediaBase64(String groupId, int fileType, String base64Str) {
        try {
            return api.uploadGroupMediaBase64(groupId, fileType, base64Str);
        } catch (RuntimeException e) {
            log.error("Failed to upload group media {}", groupId, e);
            return null;
        }
    }
}

