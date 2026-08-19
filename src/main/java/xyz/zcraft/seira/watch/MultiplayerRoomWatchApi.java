package xyz.zcraft.seira.watch;

public interface MultiplayerRoomWatchApi {
    RoomWatchSnapshot getSnapshot(MultiplayerRoomVersion version, long roomId);

    byte[] renderResult(MultiplayerRoomVersion version, long roomId, long playlistItemId);
}
