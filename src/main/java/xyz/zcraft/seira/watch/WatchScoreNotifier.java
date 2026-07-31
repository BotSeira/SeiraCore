package xyz.zcraft.seira.watch;

@FunctionalInterface
public interface WatchScoreNotifier {
    boolean sendScore(String groupId, byte[] imageBytes);
}
