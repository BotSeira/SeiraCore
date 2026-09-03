package xyz.zcraft.seira.bot.data;

import com.google.gson.annotations.SerializedName;

public record SentMessage(
        String id,
        String timestamp,
        @SerializedName("ext_info") MessageExtInfo extInfo
) {
    public record MessageExtInfo(
            @SerializedName("ref_idx") String refIdx
    ) {
    }
}
