package xyz.zcraft.bot;

import xyz.zcraft.config.AppConfig;
import xyz.zcraft.data.Button;
import xyz.zcraft.data.PendingMessage;

import java.util.ArrayList;
import java.util.List;

final class CommandUiFactory {
    private final AppConfig config;

    CommandUiFactory(AppConfig config) {
        this.config = config;
    }

    PendingMessage markdownInfoMessage(String text, List<List<Button>> buttons) {
        return PendingMessage.ofMarkdownRaw(text, buttons);
    }

    String buildBeatmapInfoText(String beatmapId, String mod, String queryText) {
        StringBuilder sb = new StringBuilder("铺面查询完成");
        if (beatmapId != null && !beatmapId.isBlank()) {
            sb.append("\n铺面ID：").append(beatmapId);
        }
        if (mod != null && !mod.isBlank()) {
            sb.append("\nMod：").append(mod);
        }
        if (queryText != null && !queryText.isBlank()) {
            sb.append("\n参数：").append(queryText);
        }
        return sb.toString();
    }

    List<List<Button>> boButtons() {
        return Button.keyboard(Button.row(
                Button.command(1, "查询最好成绩", "查询最好成绩", "/s bo1"),
                Button.command(2, "渲染最好成绩", "渲染最好成绩", "/r bo1")
        ));
    }

    List<List<Button>> rsButtons() {
        return Button.keyboard(Button.row(
                Button.command(1, "查询最近成绩", "查询最近成绩", "/s rs1"),
                Button.command(2, "渲染最近成绩", "渲染最近成绩", "/r rs1")
        ));
    }

    List<List<Button>> beatmapButtons(String beatmapId) {
        if (beatmapId == null || beatmapId.isBlank()) {
            return null;
        }

        String directUrl = config.seira().directUrl();
        if (directUrl.endsWith("/")) {
            directUrl = directUrl.substring(0, directUrl.length() - 1);
        }

        return Button.keyboard(
                Button.row(
                        Button.command(1, "查询排行榜", "查询排行榜", "/lb " + beatmapId),
                        Button.openUrl(2, "在游戏中查看", "在游戏中查看", directUrl + "/b/" + beatmapId)
                ),
                Button.row(Button.command(3, "查询自己的分数", "查询自己的分数", "/s m" + beatmapId))
        );
    }

    List<List<Button>> sButtons(String beatmapId, String scoreId) {
        if (beatmapId == null || beatmapId.isBlank()) {
            return null;
        }

        return Button.keyboard(
                Button.row(
                        Button.command(1, "查看铺面", "查看铺面", "/m " + beatmapId),
                        Button.command(2, "查看铺面集", "查看铺面集", "/ms m" + beatmapId)
                ),
                Button.row(
                        Button.command(1, "查询排行", "查询排行", "/lb " + beatmapId),
                        Button.command(2, "渲染回放", "渲染回放", "/r " + scoreId)
                )
        );
    }

    List<List<Button>> lbButtons(String beatmapId) {
        if (beatmapId == null || beatmapId.isBlank()) {
            return null;
        }

        return Button.keyboard(Button.row(
                Button.command(1, "渲染同屏回放", "渲染同屏回放", "/rsc " + beatmapId)
        ));
    }

    List<List<Button>> replayProgressButtons(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            return null;
        }

        return Button.keyboard(Button.row(
                Button.command(1, "查询渲染进度", "查询渲染进度", "/rstat " + jobId)
        ));
    }

    List<List<Button>> searchButtons(List<String> beatmapsetIds, int itemCount) {
        if (beatmapsetIds == null || beatmapsetIds.isEmpty() || itemCount <= 0) {
            return null;
        }

        int count = Math.min(10, Math.min(itemCount, beatmapsetIds.size()));

        List<List<Button>> rows = new ArrayList<>();
        List<Button> currentRow = new ArrayList<>(5);
        int buttonIndex = 0;
        for (int i = 0; i < count; i++) {
            String beatmapsetId = beatmapsetIds.get(i);
            if (beatmapsetId == null || beatmapsetId.isBlank()) {
                continue;
            }

            buttonIndex++;
            currentRow.add(Button.command(buttonIndex, String.valueOf(buttonIndex), String.valueOf(buttonIndex), "/ms " + beatmapsetId));
            if (currentRow.size() == 5) {
                rows.add(List.copyOf(currentRow));
                currentRow.clear();
            }
        }

        if (!currentRow.isEmpty()) {
            rows.add(List.copyOf(currentRow));
        }

        return rows.isEmpty() ? null : List.copyOf(rows);
    }
}

