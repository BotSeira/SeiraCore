package xyz.zcraft.seira.bot.data;

import lombok.Data;

import java.util.List;

@Data
public class PendingMessage {
    public static final int MSG_TYPE_TEXT = 0;
    public static final int MSG_TYPE_MEDIA = 7;
    public static final int MSG_TYPE_MARKDOWN = 2;
    public static final int FILE_TYPE_IMAGE = 1;
    public static final int FILE_TYPE_VIDEO = 2;
    public static final int FILE_TYPE_VOICE = 3;
    private static final int FILE_TYPE_FILE = 4;

    private String content;
    private int msgType;
    private String fileUrl = null;
    private String fileBase64 = null;
    private FileInfo uploadedMedia = null;
    private int fileType = -1;
    private boolean upload = true;

    public static PendingMessage ofString(String content) {
        final PendingMessage message = new PendingMessage();
        message.content = content;
        message.msgType = MSG_TYPE_TEXT;
        return message;
    }

    public static PendingMessage ofMarkdownRaw(String content) {
        return MDMessage.ofMarkdown(content, null);
    }

    public static PendingMessage ofMarkdownRaw(String content, List<List<Button>> buttons) {
        return MDMessage.ofMarkdown(content, buttons);
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

    public static PendingMessage ofUploadedVideo(FileInfo fileInfo) {
        if (fileInfo == null || fileInfo.getFileInfo() == null || fileInfo.getFileInfo().isBlank()) {
            throw new IllegalArgumentException("Uploaded video file info is required");
        }
        final PendingMessage message = new PendingMessage();
        message.fileType = FILE_TYPE_VIDEO;
        message.msgType = MSG_TYPE_MEDIA;
        message.uploadedMedia = fileInfo;
        return message;
    }

    public static PendingMessage ofVoiceUrl(String voiceUrl) {
        final PendingMessage message = new PendingMessage();
        message.fileType = FILE_TYPE_VOICE;
        message.msgType = MSG_TYPE_MEDIA;
        message.fileUrl = voiceUrl;
        return message;
    }

    public static PendingMessage ofFileUrl(String fileUrl) {
        final PendingMessage message = new PendingMessage();
        message.fileType = FILE_TYPE_FILE;
        message.msgType = MSG_TYPE_MEDIA;
        message.fileUrl = fileUrl;
        return message;
    }

    public PendingMessage doUpload(boolean upload) {
        this.upload = upload;
        return this;
    }
}
