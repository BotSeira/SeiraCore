package xyz.zcraft.seira.discord;

import java.util.List;

public record DiscordIncomingMessage(
        String guildId,
        String channelId,
        String authorId,
        String authorName,
        String text,
        List<BridgeAttachment> attachments
) {
    public DiscordIncomingMessage {
        text = text == null ? "" : text;
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }
}
