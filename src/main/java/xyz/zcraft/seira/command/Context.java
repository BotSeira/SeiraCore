package xyz.zcraft.seira.command;

public record Context(
        String senderUserId,
        String groupId,
        String messageId,
        String command,
        String[] args,
        String query) {
    public boolean inGroup() {
        return groupId != null && !groupId.isBlank();
    }
}
