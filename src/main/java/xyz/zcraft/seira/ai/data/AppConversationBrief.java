package xyz.zcraft.seira.ai.data;

import com.google.gson.annotations.SerializedName;


/*
{
    "AppConversationID" : "danob6v71098vo7jfsq0",
    "ConversationName" : "新的会话",
    "CreateTime" : "2026-09-20 15:04:59",
    "CreateTimestamp" : 1789887899,
    "LastChatTime" : "",
    "LastChatTimestamp" : 0,
    "EmptyConversation" : false,
    "IsPinned" : false,
    "ConversationID" : "01M2YT3SNDMQ34FZF2WJD8FTKF"
  }
 */
public record AppConversationBrief(
        @SerializedName("AppConversationID") String appConversationID,
        @SerializedName("ConversationID") String conversationID,
        @SerializedName("ConversationName") String conversationName
) {
}
