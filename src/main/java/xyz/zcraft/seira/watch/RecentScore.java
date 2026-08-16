package xyz.zcraft.seira.watch;

import com.google.gson.annotations.SerializedName;

public record RecentScore(
        @SerializedName("beatmap_id") long beatmapId,
        @SerializedName("beatmapset_id") long beatmapsetId,
        @SerializedName("score_id") long scoreId,
        @SerializedName("user_id") long userId,
        @SerializedName("full_name") String fullName,
        @SerializedName("total_score") long totalScore,
        String rank,
        double accuracy,
        @SerializedName("max_combo") int maxCombo,
        double pp,
        String mods
) {
}
