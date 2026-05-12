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
                "> b" + response.getScoreIds().size() + "查询完成\n玩家: " + response.getUserId(),
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
                "> 铺面查询完成\n铺面: " + response.getBeatmapId(),
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
                "> 排行榜查询完成" + (response.getBeatmapId() == null ? "" : "\n铺面: " + response.getBeatmapId()),
                Buttons.lbButtons(response.getBeatmapId())
        );
    }

    public PendingMessage replayMessage(APIHelper.ReplayTaskInfo taskInfo) {
        String queuedText = "生成请求已提交。\n```text";
        if (taskInfo.position() != null) {
            queuedText += "\n队列位置: " + taskInfo.position();
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

    public PendingMessage searchMessage(Response response, SearchQuery searchQuery) {
        return PendingMessage.ofMarkdownRaw(
                response.getContent(),
                Buttons.searchButtons(response, searchQuery)
        );
    }

    public PendingMessage beatmapsetMessage(Response response) {
        return PendingMessage.ofMarkdownRaw(
                "> 铺面集查询完成\nID: " + response.getBeatmapsetId(),
                Buttons.beatmapsetButtons(response.getBeatmapStars(), response.getBeatmapIds())
        );
    }

    private static final class Buttons {
        static List<List<Button>> searchButtons(Response response, SearchQuery query) {
            List<List<Button>> rows = new ArrayList<>();

            List<Button> navRow = new ArrayList<>(3);

            if (query.page() > 1) {
                navRow.add(Button.command(11, "上一页", "/sms #" + (query.page() - 1) + " " + query.query()));
            } else {
                navRow.add(Button.command(11, false, "上一页", ""));
            }

            final String label = query.page() + "/" + ((int) Math.ceil(response.getBeatmapsetIds().size() / 10.0));
            navRow.add(Button.command(12, false, label, "/sms #" + query.page() + " " + query.query()));

            if (query.page() * 10 < ids.size()) {
                navRow.add(Button.command(13, "下一页", "/sms #" + (query.page() + 1) + " " + query.query()));
            } else {
                navRow.add(Button.command(13, false, "下一页", ""));
            }

            rows.add(List.copyOf(navRow));

            return rows;
        }
        
        static List<List<Button>> boButtons() {
            return Button.keyboard(Button.row(
                    Button.command(1, "查询最好成绩", "/s bo1"),
                    Button.command(2, "渲染最好成绩", "/r bo1")
            ));
        }

        static List<List<Button>> rsButtons() {
            return Button.keyboard(Button.row(
                    Button.command(1, "查询最近成绩", "/s rs1"),
                    Button.command(2, "渲染最近成绩", "/r rs1")
            ));
        }

        static List<List<Button>> sButtons(String beatmapId, String scoreId) {
            if (beatmapId == null || beatmapId.isBlank()) {
                return null;
            }

            return Button.keyboard(
                    Button.row(
                            Button.command(1, "查看铺面", "/m " + beatmapId),
                            Button.command(2, "查看铺面集", "/ms m" + beatmapId)
                    ),
                    Button.row(
                            Button.command(1, "查询排行", "/lb " + beatmapId),
                            Button.command(2, "渲染回放", "/r " + scoreId)
                    )
            );
        }

        static List<List<Button>> lbButtons(String beatmapId) {
            if (beatmapId == null || beatmapId.isBlank()) {
                return null;
            }

            return Button.keyboard(Button.row(
                    Button.command(1, "渲染同屏回放", "/rsc " + beatmapId)
            ));
        }

        static List<List<Button>> replayProgressButtons(String jobId) {
            if (jobId == null || jobId.isBlank()) {
                return null;
            }

            return Button.keyboard(Button.row(
                    Button.command(1, "查询渲染进度", "/rstat " + jobId)
            ));
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
                            Button.command(1, "查询排行榜", "/lb " + beatmapId),
                            Button.openUrl(2, "在游戏中查看", directUrl + "/b/" + beatmapId)
                    ),
                    Button.row(Button.command(3, "查询自己的分数", "/s m" + beatmapId))
            );
        }

        public static List<List<Button>> beatmapsetButtons(List<String> stars, List<String> ids) {
            if (ids == null || ids.isEmpty()) {
                return null;
            }

            List<List<Button>> rows = new ArrayList<>();
            List<Button> currentRow = new ArrayList<>(5);
            int buttonIndex = 0;
            for (int i = 0; i < ids.size(); i++) {
                String beatmapId = ids.get(i);
                if (beatmapId == null || beatmapId.isBlank()) {
                    continue;
                }

                buttonIndex++;
                currentRow.add(Button.command(buttonIndex, stars.get(i) + "★", "/m " + beatmapId));
                if (currentRow.size() == 5) {
                    rows.add(List.copyOf(currentRow));
                    currentRow.clear();

                    if (rows.size() >= 5) {
                        break;
                    }
                }
            }

            if (!currentRow.isEmpty()) {
                fillDummyButtons(currentRow);
                rows.add(List.copyOf(currentRow));
            }

            return rows;
        }

        private static void fillDummyButtons(List<Button> buttons) {
            final int c = 5 - buttons.size();
            for (int i = 0; i < c; i++) {
                buttons.add(Button.command(i + 100, false, "", ""));
            }
        }
    }
}

