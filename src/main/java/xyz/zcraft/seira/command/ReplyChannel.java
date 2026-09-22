package xyz.zcraft.seira.command;

import xyz.zcraft.seira.bot.data.MessageReference;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.data.SendResult;

import java.util.concurrent.atomic.AtomicInteger;

public class ReplyChannel {
    private final TaskCoordinator taskCoordinator;
    private final String targetId;
    private final String messageId;
    private final String refMsgIdx;
    private final boolean groupMessage;
    private final boolean queueMessageInGroup;
    private final AtomicInteger passiveSequence = new AtomicInteger(1);

    ReplyChannel(TaskCoordinator taskCoordinator,
                 String targetId,
                 String messageId,
                 boolean groupMessage,
                 boolean queueMessageInGroup,
                 String refMsgIdx
    ) {
        this.taskCoordinator = taskCoordinator;
        this.targetId = targetId;
        this.messageId = messageId;
        this.groupMessage = groupMessage;
        this.queueMessageInGroup = queueMessageInGroup;
        this.refMsgIdx = refMsgIdx;
    }

    public synchronized SendResult sendReply(PendingMessage message) {
        return taskCoordinator.sendOutboundMessage(targetId, messageId, groupMessage, message, passiveSequence);
    }

    public synchronized SendResult sendReply(PendingMessage message, boolean ref) {
        if (ref) message.ref(new MessageReference(refMsgIdx));
        return taskCoordinator.sendOutboundMessage(targetId, messageId, groupMessage, message, passiveSequence);
    }

    public synchronized SendResult sendProactive(PendingMessage message) {
        return sendReply(message, false);
    }

    public synchronized SendResult sendProactive(PendingMessage message, boolean ref) {
        if (ref) message.ref(new MessageReference(refMsgIdx));
        return taskCoordinator.sendOutboundMessage(targetId, null, groupMessage, message, null);
    }

    public synchronized SendResult sendQueueNotice(PendingMessage message) {
        if (groupMessage && !queueMessageInGroup) {
            return new SendResult(true, null);
        }
        return sendReply(message);
    }

}
