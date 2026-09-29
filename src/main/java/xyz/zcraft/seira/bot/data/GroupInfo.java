package xyz.zcraft.seira.bot.data;

import com.google.gson.annotations.SerializedName;

import java.util.List;

public record GroupInfo(
        @SerializedName("group_openid") String groupOpenId,
        @SerializedName("group_name") String groupName,
        @SerializedName("group_finger_memo") String groupFingerMemo,
        @SerializedName("group_class_text") String groupClassText,
        @SerializedName("group_tags") List<String> groupTags,
        @SerializedName("group_member_num") Integer groupMemberNum
) {
}
