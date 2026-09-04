package xyz.zcraft.seira.watch;

import xyz.zcraft.seira.bot.MessageSender;
import xyz.zcraft.seira.bot.data.FileInfo;
import xyz.zcraft.seira.bot.data.Message;
import xyz.zcraft.seira.bot.data.PendingMessage;

import java.util.Base64;
import java.util.Objects;

public final class QqMultiplayerRoomNotifier implements MultiplayerRoomNotifier {
    private final MessageSender messageSender;

    public QqMultiplayerRoomNotifier(MessageSender messageSender) {
        this.messageSender = Objects.requireNonNull(messageSender);
    }

    @Override
    public boolean sendResult(String groupId, byte[] imageBytes) {
        FileInfo media = messageSender.uploadGroupMediaBase64(
                groupId,
                PendingMessage.FILE_TYPE_IMAGE,
                Base64.getEncoder().encodeToString(imageBytes)
        );
        if (media == null) {
            return false;
        }

        Message message = new Message();
        message.setMsgType(PendingMessage.MSG_TYPE_MEDIA);
        message.setMedia(media);
        return messageSender.sendGroupMessage(groupId, message) != null;
    }

    @Override
    public boolean sendRoomEnded(String groupId, RoomWatchSnapshot snapshot) {
        return messageSender.sendGroupText(
                groupId,
                "多人房间“" + snapshot.roomName() + "” (#" + snapshot.roomId() + ") 已结束，监视已自动停止。"
        ) != null;
    }
}
