package xyz.zcraft.data;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import lombok.Data;

@Data
public class Message {
    private String content;

    @SerializedName("msg_type")
    private int msgType;

    private JsonObject markdown;

    private JsonObject keyboard;

    private Object ark;

    private FileInfo media;

    @SerializedName("event_id")
    private String eventId;

    @SerializedName("msg_id")
    private String msgId;

    @SerializedName("msg_seq")
    private Integer msgSeq;

    @SerializedName("is_wakeup")
    private Boolean isWakeup;
}
