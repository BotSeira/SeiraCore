package xyz.zcraft.seira.bot.data;

import com.google.gson.annotations.SerializedName;

import java.util.Optional;

public record MessageReference(
        @SerializedName("message_id") String messageId
) {
    public static MessageReference of(SentMessage message) {
        return Optional.ofNullable(message)
                .map(SentMessage::extInfo)
                .map(SentMessage.MessageExtInfo::refIdx)
                .map(MessageReference::new)
                .orElse(null);
    }

    public static MessageReference of(String messageId) {
        return Optional.ofNullable(messageId)
                .map(MessageReference::new)
                .orElse(null);
    }
}
