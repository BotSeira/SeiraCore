package xyz.zcraft.seira.command;

import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.data.SendResult;

import java.util.Objects;

public record Context(
        String senderUserId,
        String groupId,
        String messageId,
        String command,
        String[] args,
        String query,
        String rawContent,
        ReplyChannel replies) {
    public Context(
            String senderUserId,
            String groupId,
            String messageId,
            String command,
            String[] args,
            String rawContent,
            String query
    ) {
        this(senderUserId, groupId, messageId, command, args, query, rawContent, null);
    }

    public Context {
        args = args == null ? new String[0] : args.clone();
        query = query == null ? "" : query;
    }

    @Override
    public String[] args() {
        return args.clone();
    }

    public int argumentCount() {
        return args.length;
    }

    public String argument(int index) {
        return args[index];
    }

    public boolean inGroup() {
        return groupId != null && !groupId.isBlank();
    }

    public Context withReplies(ReplyChannel replyChannel) {
        return new Context(
                senderUserId, groupId, messageId, command, args, query, rawContent,
                Objects.requireNonNull(replyChannel, "replyChannel")
        );
    }

    public Context asCommand(String nextCommand, String[] nextArgs, String nextQuery) {
        return new Context(
                senderUserId, groupId, messageId, nextCommand, nextArgs, nextQuery, rawContent, replies
        );
    }

    /**
     * Sends a passive reply associated with the message that invoked this command.
     */
    public SendResult sendReply(PendingMessage message) {
        return sendReply(message, false);
    }

    public SendResult sendReply(PendingMessage message, boolean ref) {
        return requireReplies().sendReply(Objects.requireNonNull(message, "message"), ref);
    }

    public SendResult sendReply(String message) {
        return requireReplies().sendReply(PendingMessage.ofMarkdownRaw(message));
    }

    public SendResult send(boolean replyFirst, PendingMessage message) {
        return send(replyFirst, message, false);
    }

    public SendResult send(boolean replyFirst, PendingMessage message, boolean ref) {
        SendResult sendResult;
        if (replyFirst) {
            sendResult = sendReply(message, ref);
            if (!sendResult.success()) {
                sendResult = sendMessage(message, ref);
            }
        } else {
            sendResult = sendMessage(message, ref);
            if (!sendResult.success()) {
                sendResult = sendReply(message, ref);
            }
        }
        return sendResult;
    }

    /**
     * Sends an active message to the same user or group, without an inbound message reference.
     */
    public SendResult sendMessage(PendingMessage message, boolean ref) {
        return requireReplies().sendProactive(Objects.requireNonNull(message, "message"), ref);
    }

    public SendResult sendMessage(PendingMessage message) {
        return requireReplies().sendProactive(Objects.requireNonNull(message, "message"));
    }

    public SendResult sendQueueNotice(PendingMessage message) {
        return requireReplies().sendQueueNotice(Objects.requireNonNull(message, "message"));
    }

    private ReplyChannel requireReplies() {
        if (replies == null) {
            throw new IllegalStateException("This command context is not bound to a reply channel");
        }
        return replies;
    }
}
