package xyz.zcraft.seira.watch;

public interface MultiplayerRoomWatchApi {
    RoomWatchSnapshot getSnapshot(long roomId);

    byte[] renderResult(long roomId, long playlistItemId);
}
