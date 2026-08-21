package xyz.zcraft.seira.watch;

public interface MultiplayerRoomNotifier {
    boolean sendResult(String groupId, byte[] imageBytes);

    boolean sendRoomEnded(String groupId, RoomWatchSnapshot snapshot);
}
