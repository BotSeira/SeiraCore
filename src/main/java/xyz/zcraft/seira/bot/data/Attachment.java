package xyz.zcraft.seira.bot.data;

import com.google.gson.annotations.SerializedName;

public record Attachment(
        @SerializedName("content_type") String contentType,
        String filename,
        long size,
        String url
) {
}
