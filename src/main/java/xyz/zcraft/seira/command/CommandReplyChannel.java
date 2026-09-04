package xyz.zcraft.seira.command;

import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.data.SendResult;

/**
 * The outbound side of one command invocation.
 *
 * <p>A reply is associated with the inbound QQ message. A proactive message is
 * sent to the same conversation without that association and therefore does
 * not consume the passive reply sequence.</p>
 */
public interface CommandReplyChannel {
    SendResult sendReply(PendingMessage message);

    SendResult sendProactive(PendingMessage message);

    SendResult sendQueueNotice(PendingMessage message);
}
