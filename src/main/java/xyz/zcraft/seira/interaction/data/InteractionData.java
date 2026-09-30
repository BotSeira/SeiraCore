package xyz.zcraft.seira.interaction.data;

import com.google.gson.annotations.SerializedName;

public record InteractionData(
        Integer type,
        InteractionResolved resolved
) {
    public record InteractionResolved(
            @SerializedName("button_data") String buttonData,
            @SerializedName("button_id") String buttonId,
            @SerializedName("message_id") String messageId
    ){}
}
