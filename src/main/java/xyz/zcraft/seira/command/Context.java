package xyz.zcraft.seira.command;

import xyz.zcraft.seira.bot.data.PendingMessage;

import java.util.Objects;

public record Context(
        String senderUserId,
        String groupId,
        String messageId,
        String command,
        String[] args,
        String query,
        CommandReplyChannel replies) {
    public Context(
            String senderUserId,
            String groupId,
            String messageId,
            String command,
            String[] args,
            String query
    ) {
        this(senderUserId, groupId, messageId, command, args, query, null);
    }

    public Context {
        command = Objects.requireNonNull(command, "command");
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

    public Context withReplies(CommandReplyChannel replyChannel) {
        return new Context(
                senderUserId, groupId, messageId, command, args, query,
                Objects.requireNonNull(replyChannel, "replyChannel")
        );
    }

    public Context asCommand(String nextCommand, String[] nextArgs, String nextQuery) {
        return new Context(
                senderUserId, groupId, messageId, nextCommand, nextArgs, nextQuery, replies
        );
    }

    /** Sends a passive reply associated with the message that invoked this command. */
    public boolean sendReply(PendingMessage message) {
        return requireReplies().sendReply(Objects.requireNonNull(message, "message"));
    }

    public boolean sendReply(String message) {
        return requireReplies().sendReply(PendingMessage.ofString(message));
    }

    /** Sends an active message to the same user or group, without an inbound message reference. */
    public boolean sendMessage(PendingMessage message) {
        return requireReplies().sendProactive(Objects.requireNonNull(message, "message"));
    }

    public boolean sendQueueNotice(PendingMessage message) {
        return requireReplies().sendQueueNotice(Objects.requireNonNull(message, "message"));
    }

    private CommandReplyChannel requireReplies() {
        if (replies == null) {
            throw new IllegalStateException("This command context is not bound to a reply channel");
        }
        return replies;
    }
}
