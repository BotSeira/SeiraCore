package xyz.zcraft.seira.bot.data;


import com.google.gson.annotations.SerializedName;

import java.util.List;

public record MsgElem(
        List<Attachment> attachments,
        String content,
        @SerializedName("message_type") Integer messageType,
        @SerializedName("msg_idx") String msgIdx
) {
}
