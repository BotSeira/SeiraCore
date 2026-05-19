package xyz.zcraft.model;

import com.google.gson.annotations.SerializedName;

import java.util.Objects;

public record OsuUser(
        @SerializedName("avatar_url") String avatarUrl,
        @SerializedName("country_code") String countryCode,
        @SerializedName("default_group") String defaultGroup,
        long id,
        @SerializedName("is_active") boolean isActive,
        @SerializedName("is_bot") boolean isBot,
        @SerializedName("is_deleted") boolean isDeleted,
        @SerializedName("is_online") boolean isOnline,
        @SerializedName("is_supporter") boolean isSupporter,
        @SerializedName("last_visit") String lastVisit,
        @SerializedName("pm_friends_only") boolean pmFriendsOnly,
        @SerializedName("profile_colour") String profileColour, String username
) {
    @Override
    public boolean equals(Object obj) {
        if (obj instanceof OsuUser another) {
            return Objects.equals(this.id, another.id);
        }
        return false;
    }
}
