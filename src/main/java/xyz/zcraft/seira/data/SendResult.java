package xyz.zcraft.seira.data;

import xyz.zcraft.seira.bot.data.msg.SentMessage;

public record SendResult(
        boolean success,
        SentMessage sentMessage
) {
}
