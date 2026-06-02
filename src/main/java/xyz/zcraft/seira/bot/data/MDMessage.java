package xyz.zcraft.seira.bot.data;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class MDMessage extends PendingMessage {
    private final static Gson GSON = new Gson();
    private String markdown;
    private List<List<Button>> buttons;

    public static MDMessage ofMarkdown(String markdown, List<List<Button>> buttons) {
        MDMessage message = new MDMessage();
        message.setMsgType(MSG_TYPE_MARKDOWN);
        message.setMarkdown(markdown);
        message.setButtons(buttons);
        return message;
    }

    public boolean hasKeyboard() {
        return buttons != null && buttons.stream().anyMatch(row -> row != null && !row.isEmpty());
    }

    public JsonObject getKeyboard() {
        if (!hasKeyboard())
            return null;

        JsonObject keyboard = new JsonObject();
        JsonObject content = new JsonObject();
        JsonArray rows = new JsonArray();

        for (List<Button> rowButtons : buttons) {
            if (rowButtons == null || rowButtons.isEmpty()) {
                continue;
            }
            JsonObject row = new JsonObject();
            JsonArray btnArr = new JsonArray();
            rowButtons.stream()
                    .map(GSON::toJsonTree)
                    .forEach(btnArr::add);

            row.add("buttons", btnArr);
            rows.add(row);
        }

        content.add("rows", rows);
        keyboard.add("content", content);
        return keyboard;
    }
}
