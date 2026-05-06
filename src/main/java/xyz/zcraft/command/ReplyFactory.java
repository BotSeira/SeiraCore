package xyz.zcraft.command;

import xyz.zcraft.api.APIHelper;
import xyz.zcraft.api.Response;
import xyz.zcraft.config.AppConfig;
import xyz.zcraft.data.Button;
import xyz.zcraft.data.PendingMessage;

import java.util.ArrayList;
import java.util.List;

final class ReplyFactory {
    private final AppConfig config;

    ReplyFactory(AppConfig config) {
        this.config = config;
    }

    public PendingMessage boMessage(Response response) {
        return PendingMessage.ofMarkdownRaw(
                "> BoN 查询完成\n玩家: " + response.getUserId() + "\n数量: " + response.getScoreIds().size(),
                Buttons.boButtons()
        );
    }

    public PendingMessage rsMessage(Response response) {
        return PendingMessage.ofMarkdownRaw(
                "> 最近成绩查询完成\n玩家: " + response.getUserId() + "\n数量: " + response.getScoreIds().size(),
                Buttons.rsButtons()
        );
    }

    public PendingMessage beatmapMessage(Response response) {
        return PendingMessage.ofMarkdownRaw(
                "> 铺面查询完成\nID: " + response.getBeatmapId(),
                Buttons.beatmapButtons(response.getBeatmapId(), config.seira().directUrl())
        );

    }

    public PendingMessage scoreMessage(Response response) {
        return PendingMessage.ofMarkdownRaw(
                "> 成绩查询完成\n铺面: " + response.getBeatmapId() + "\n成绩: " + response.getScoreId(),
                Buttons.sButtons(response.getBeatmapId(), response.getScoreId())
        );
    }

    public PendingMessage lbMessage(Response response) {
        return PendingMessage.ofMarkdownRaw(
                "> 排行榜查询完成\n铺面: " + response.getBeatmapId(),
                Buttons.lbButtons(response.getBeatmapId())
        );
    }

    public PendingMessage replayMessage(APIHelper.ReplayTaskInfo taskInfo) {
        String queuedText = "生成请求已提交。";
        if (taskInfo.position() != null) {
            queuedText += "\n```text\n队列位置: " + taskInfo.position();
        }
        if (taskInfo.taskId() != null) {
            queuedText += "\n请求: " + taskInfo.taskId();
        }
        if (taskInfo.message() != null) {
            queuedText += "\n" + taskInfo.message();
        }
        queuedText += "\n```";
        return PendingMessage.ofMarkdownRaw(queuedText, Buttons.replayProgressButtons(taskInfo.taskId()));
    }

    public PendingMessage replayStatMessage(String jobId, String renderStat) {
        return PendingMessage.ofMarkdownRaw(renderStat, Buttons.replayProgressButtons(jobId));
    }

    public PendingMessage searchMessage(Response response) {
        return PendingMessage.ofMarkdownRaw(
                response.getContent(),
                Buttons.searchButtons(response.getBeatmapsetIds(), Math.min(response.getBeatmapsetIds().size(), 10))
        );
    }

    public PendingMessage beatmapsetMessage(Response response) {
        return PendingMessage.ofMarkdownRaw(
                "> 铺面集查询完成\nID: " + response.getBeatmapsetId(),
                null
        );
    }

    private static final class Buttons {
        static List<List<Button>> boButtons() {
            return Button.keyboard(Button.row(
                    Button.command(1, "查询最好成绩", "查询最好成绩", "/s bo1"),
                    Button.command(2, "渲染最好成绩", "渲染最好成绩", "/r bo1")
            ));
        }
        static List<List<Button>> rsButtons() {
            return Button.keyboard(Button.row(
                    Button.command(1, "查询最近成绩", "查询最近成绩", "/s rs1"),
                    Button.command(2, "渲染最近成绩", "渲染最近成绩", "/r rs1")
            ));
        }
        static List<List<Button>> sButtons(String beatmapId, String scoreId) {
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
        static List<List<Button>> lbButtons(String beatmapId) {
            if (beatmapId == null || beatmapId.isBlank()) {
                return null;
            }

            return Button.keyboard(Button.row(
                    Button.command(1, "渲染同屏回放", "渲染同屏回放", "/rsc " + beatmapId)
            ));
        }
        static List<List<Button>> replayProgressButtons(String jobId) {
            if (jobId == null || jobId.isBlank()) {
                return null;
            }

            return Button.keyboard(Button.row(
                    Button.command(1, "查询渲染进度", "查询渲染进度", "/rstat " + jobId)
            ));
        }
        static List<List<Button>> searchButtons(List<String> beatmapsetIds, int itemCount) {
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
        static List<List<Button>> beatmapButtons(String beatmapId, String directUrl) {
            if (beatmapId == null || beatmapId.isBlank()) {
                return null;
            }

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
    }
}

