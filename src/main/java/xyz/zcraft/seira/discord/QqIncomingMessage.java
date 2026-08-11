package xyz.zcraft.seira.discord;

import xyz.zcraft.seira.bot.data.Attachment;

import java.util.List;

public record QqIncomingMessage(
        String groupId,
        String senderId,
        String senderName,
        String messageId,
        String text,
        List<Attachment> attachments
) {
    public QqIncomingMessage {
        text = text == null ? "" : text;
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }
}
