package xyz.zcraft.seira.watch;

import com.google.gson.annotations.SerializedName;

public record CompletedRoomPlay(
        @SerializedName("playlist_item_id") long playlistItemId,
        @SerializedName("played_at") String playedAt
) {
    public CompletedRoomPlay {
        if (playlistItemId <= 0) {
            throw new IllegalArgumentException("playlistItemId must be positive");
        }
    }
}
