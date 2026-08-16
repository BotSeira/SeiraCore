package xyz.zcraft.seira.bot.data;

import com.google.gson.annotations.SerializedName;

public record PanelItem(
        String name,
        String desc,
        String type,
        @SerializedName("only_admin") Boolean onlyAdmin,
        String link
) {
}
