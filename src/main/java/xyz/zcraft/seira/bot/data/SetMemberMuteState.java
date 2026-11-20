package xyz.zcraft.seira.bot.data;

import com.google.gson.annotations.SerializedName;

public record SetMemberMuteState(
        String op,
        @SerializedName("member_openid") String memberOpenid,
        @SerializedName("mute_expire_at") String muteExpireAt
) {
}
