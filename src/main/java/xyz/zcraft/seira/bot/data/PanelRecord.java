package xyz.zcraft.seira.bot.data;


import com.google.gson.annotations.SerializedName;

public record PanelRecord(
        @SerializedName("panel_id") String panelId,
        @SerializedName("scope") String scope,
        @SerializedName("target_type") String targetType,
        @SerializedName("panel") Panel panel,
        @SerializedName("created_at") String createdAt,
        @SerializedName("updated_at") String updatedAt,
        @SerializedName("version") Integer version
) {
}
