package xyz.zcraft.seira.ai.data;

import com.google.gson.annotations.SerializedName;

public record AgentFile(
        @SerializedName("Name") String name,
        @SerializedName("Path") String path,
        @SerializedName("Size") Long size,
        @SerializedName("Url") String url
) {
}
