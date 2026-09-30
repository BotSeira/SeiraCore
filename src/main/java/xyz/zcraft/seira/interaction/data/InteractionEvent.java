package xyz.zcraft.seira.interaction.data;

import com.google.gson.annotations.SerializedName;

public record InteractionEvent(
        @SerializedName("id") String id,
        @SerializedName("type") Integer type,
        @SerializedName("scene") String scene,
        @SerializedName("chat_type") Integer chatType,
        @SerializedName("timestamp") String timestamp,
        @SerializedName("guild_id") String guildId,
        @SerializedName("channel_id") String channelId,
        @SerializedName("user_openid") String userOpenId,
        @SerializedName("group_openid") String groupOpenId,
        @SerializedName("group_member_openid") String groupMemberOpenId,
        @SerializedName("data") InteractionData data,
        @SerializedName("version") Integer version,
        @SerializedName("application_id") String application_id
) {
    public String resolveUserId() {
        return switch (scene) {
            case "c2c" -> userOpenId;
            case "group" -> groupMemberOpenId;
            default -> null;
        };
    }

    public boolean inGroup() {
        return "group".equals(scene);
    }
}
