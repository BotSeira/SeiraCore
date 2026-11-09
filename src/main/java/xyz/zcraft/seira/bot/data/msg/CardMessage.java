package xyz.zcraft.seira.bot.data.msg;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class CardMessage extends PendingMessage {
    private final static Gson GSON = new Gson();
    private String title;
    private String description;
    private String url;
    private String picUrl;

    public static CardMessage ofCard(String title, String description, String url, String picUrl) {
        CardMessage message = new CardMessage();
        message.setMsgType(MSG_TYPE_CARD);
        message.setTitle(title);
        message.setDescription(description);
        message.setUrl(url);
        message.setPicUrl(picUrl);
        return message;
    }

    public JsonObject toCardJson() {
        JsonObject content = new JsonObject();
        content.addProperty("title", title);
        content.addProperty("description", description);
        content.addProperty("url", url);
        content.addProperty("pic_url", picUrl);

        JsonObject card = new JsonObject();
        card.addProperty("type", "tuwen");
        card.add("content", content);

        return card;
    }
}
