package xyz.zcraft.seira.watch;

import xyz.zcraft.seira.bot.MessageSender;
import xyz.zcraft.seira.bot.data.FileInfo;
import xyz.zcraft.seira.bot.data.Message;
import xyz.zcraft.seira.bot.data.PendingMessage;

import java.util.Base64;
import java.util.Objects;

public final class QqWatchScoreNotifier implements WatchScoreNotifier {
    private final MessageSender messageSender;

    public QqWatchScoreNotifier(MessageSender messageSender) {
        this.messageSender = Objects.requireNonNull(messageSender);
    }

    @Override
    public boolean sendScore(String groupId, byte[] imageBytes) {
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        FileInfo media = messageSender.uploadGroupMediaBase64(
                groupId,
                PendingMessage.FILE_TYPE_IMAGE,
                base64
        );
        if (media == null) {
            return false;
        }

        Message message = new Message();
        message.setMsgType(PendingMessage.MSG_TYPE_MEDIA);
        message.setMedia(media);
        return messageSender.sendGroupMessage(groupId, message);
    }
}
