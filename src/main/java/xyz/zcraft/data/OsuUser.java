package xyz.zcraft.data;

import com.google.gson.annotations.SerializedName;

public record OsuUser(
        @SerializedName("id") Integer id,
        @SerializedName("username") String username
) {
}
