package xyz.zcraft.seira.bot;

public interface ProactiveMessenger {
    boolean sendPrivateText(String userId, String content);

    boolean sendGroupText(String groupId, String content);
}
