package xyz.zcraft.seira.bot.data;

import com.google.gson.annotations.SerializedName;

public record GroupBotState(
        @SerializedName("member_openid") String memberOpenId,
        @SerializedName("joined_at") String joinedAt,
        @SerializedName("allow_proactive_msg") Boolean allowProactiveMsg,
        @SerializedName("recv_msg_setting") ReceiveMsgSetting receiveMsgSetting,
        @SerializedName("member_role") MemberRole memberRole
) {
    public enum ReceiveMsgSetting {
        @SerializedName("all") ALL,
        @SerializedName("only_mention") ONLY_MENTION,
        @SerializedName("mention_and_context") MENTION_AND_CONTEXT,
    }

    public enum MemberRole {
        @SerializedName("member")  MEMBER,
        @SerializedName("owner") OWNER,
        @SerializedName("admin") ADMIN
    }
}
