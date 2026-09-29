package xyz.zcraft.seira.watch;

import xyz.zcraft.seira.bot.MessageSender;
import xyz.zcraft.seira.data.UploadedImage;

import java.util.Objects;

public final class MPNotifier {
    private final MessageSender messageSender;

    public MPNotifier(MessageSender messageSender) {
        this.messageSender = Objects.requireNonNull(messageSender);
    }

    public boolean sendResult(MPWatchService.WatchEntry watch, String groupId, byte[] imageBytes) {
        final UploadedImage uploadedImage = messageSender.uploadImageToCos(imageBytes);

        if (uploadedImage == null) return false;

        return messageSender.sendGroupMarkdown(
                groupId,
                """
                %s
                > __%s__
                > %s mp - %s
                """.formatted(uploadedImage.toMarkdown(), watch.roomName(), watch.version().value(), watch.roomId()).trim()
        ) != null;
    }

    public boolean sendRoomEnded(String groupId, RoomWatchSnapshot snapshot) {
        return messageSender.sendGroupText(
                groupId,
                "多人房间“" + snapshot.roomName() + "” (#" + snapshot.roomId() + ") 已结束，监视已自动停止。"
        ) != null;
    }
}
