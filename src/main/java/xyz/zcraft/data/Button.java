package xyz.zcraft.data;

import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Button {
    private String id;
    @SerializedName("render_data")
    private RenderData renderData;
    private Action action;

    public static Button command(int id, String label, String command) {
        return command(id, true, label, command);
    }

    public static Button command(int id, boolean enable, String label, String command) {
        Button button = new Button();
        button.id = String.valueOf(id);

        RenderData renderData = new RenderData();
        renderData.setLabel(label);
        renderData.setVisitedLabel(label);
        renderData.setStyle(enable ? 1 : 0);

        button.setRenderData(renderData);

        Action action = new Action();
        action.setType(2);
        action.setData(command);
        action.setEnter(true);

        Action.Permission permission = new Action.Permission();
        if (enable) {
            permission.setType(2);
        } else {
            permission.setType(0);
            permission.setSpecifyUserIds(List.of());
        }

        action.setPermission(permission);

        button.setAction(action);

        return button;
    }

    public static Button openUrl(int id, String label, String url) {
        Button button = new Button();
        button.id = String.valueOf(id);

        RenderData renderData = new RenderData();
        renderData.setLabel(label);
        renderData.setVisitedLabel(label);
        renderData.setStyle(1);

        button.setRenderData(renderData);

        Action action = new Action();
        action.setType(0);
        action.setData(url);
        action.setEnter(false);

        Action.Permission permission = new Action.Permission();
        permission.setType(2);

        action.setPermission(permission);

        button.setAction(action);

        return button;
    }

    public static List<Button> row(Button... buttons) {
        if (buttons == null || buttons.length == 0) {
            return List.of(command(0, "_", "/help"));
        }
        return List.of(buttons);
    }

    @SafeVarargs
    public static List<List<Button>> keyboard(List<Button>... rows) {
        return List.of(rows);
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class RenderData {
        private String label;
        @SerializedName("visited_label")
        private String visitedLabel;
        private int style;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class Action {
        private int type;
        private Permission permission;
        private String data;
        private boolean enter;
        private int anchor;
        @SerializedName("unsupport_tips")
        private String unsupportTips;

        @Data
        private static class Permission {
            private int type;
            @SerializedName("specify_user_ids")
            private List<String> specifyUserIds;
        }
    }
}
