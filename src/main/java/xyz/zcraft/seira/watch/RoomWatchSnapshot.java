package xyz.zcraft.seira.watch;

import com.google.gson.annotations.SerializedName;

import java.util.List;

public record RoomWatchSnapshot(
        @SerializedName("room_id") long roomId,
        @SerializedName("room_name") String roomName,
        boolean active,
        @SerializedName("completed_plays") List<CompletedRoomPlay> completedPlays
) {
    public RoomWatchSnapshot {
        roomName = roomName == null || roomName.isBlank() ? "多人房间 #" + roomId : roomName;
        completedPlays = completedPlays == null ? List.of() : List.copyOf(completedPlays);
    }
}
