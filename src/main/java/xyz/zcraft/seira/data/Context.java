package xyz.zcraft.seira.data;

public record Context(
        String senderUserId,
        String groupId
) {
    public boolean inGroup() {
        return groupId != null && !groupId.isBlank();
    }
}
