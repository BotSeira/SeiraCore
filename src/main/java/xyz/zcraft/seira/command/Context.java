package xyz.zcraft.seira.command;

import java.util.Objects;

public record Context(
        String senderUserId,
        String groupId,
        String messageId,
        String command,
        String[] args,
        String query) {
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
}
