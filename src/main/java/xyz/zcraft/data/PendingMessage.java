package xyz.zcraft.data;

import lombok.Data;

@Data
public class PendingMessage {
    public static final int MSG_TYPE_TEXT = 0;
    public static final int MSG_TYPE_MEDIA = 7;
    public static final int MSG_TYPE_MARKDOWN = 2;
    public static final int FILE_TYPE_IMAGE = 1;
    public static final int FILE_TYPE_VIDEO = 2;

    private String content;
    private int msgType;
    private String fileUrl = null;
    private String fileBase64 = null;
    private int fileType = -1;

    public static PendingMessage ofString(String content) {
        final PendingMessage message = new PendingMessage();
        message.content = content;
        message.msgType = MSG_TYPE_TEXT;
        return message;
    }

    public static PendingMessage ofMarkdownRaw(String content) {
        final PendingMessage message = new PendingMessage();
        message.content = content;
        message.msgType = MSG_TYPE_MARKDOWN;
        return message;
    }

    public static PendingMessage ofImageBase64(String imageBase64) {
        final PendingMessage message = new PendingMessage();
        message.fileType = FILE_TYPE_IMAGE;
        message.msgType = MSG_TYPE_MEDIA;
        message.fileBase64 = imageBase64;
        return message;
    }

    public static PendingMessage ofVideoUrl(String videoUrl) {
        final PendingMessage message = new PendingMessage();
        message.fileType = FILE_TYPE_VIDEO;
        message.msgType = MSG_TYPE_MEDIA;
        message.fileUrl = videoUrl;
        return message;
    }

    public static PendingMessage ofVideoBase64(String videoBase64) {
        final PendingMessage message = new PendingMessage();
        message.fileType = FILE_TYPE_VIDEO;
        message.msgType = MSG_TYPE_MEDIA;
        message.fileBase64 = videoBase64;
        return message;
    }
}
