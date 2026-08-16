package xyz.zcraft.seira.command;

import xyz.zcraft.seira.bot.data.PendingMessage;

/**
 * The outbound side of one command invocation.
 *
 * <p>A reply is associated with the inbound QQ message. A proactive message is
 * sent to the same conversation without that association and therefore does
 * not consume the passive reply sequence.</p>
 */
public interface CommandReplyChannel {
    boolean sendReply(PendingMessage message);

    boolean sendProactive(PendingMessage message);

    boolean sendQueueNotice(PendingMessage message);
}
