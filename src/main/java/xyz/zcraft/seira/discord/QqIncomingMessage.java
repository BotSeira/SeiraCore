package xyz.zcraft.seira.discord;

import xyz.zcraft.seira.bot.data.Attachment;

import java.util.List;
import java.util.Map;

public record QqIncomingMessage(
        String groupId,
        String senderId,
        String senderName,
        String messageId,
        String text,
        List<Attachment> attachments,
        Map<String, String> mentions
) {
    public QqIncomingMessage {
        text = text == null ? "" : text;
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }
}
