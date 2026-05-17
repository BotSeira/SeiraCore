package xyz.zcraft.command;

import xyz.zcraft.api.APIHelper;
import xyz.zcraft.api.Response;
import xyz.zcraft.binding.BindingHelper;
import xyz.zcraft.config.AppConfig;
import xyz.zcraft.config.BindingConfig;
import xyz.zcraft.data.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class ReplyFactory {
    private final AppConfig config;

    ReplyFactory(AppConfig config) {
        this.config = config;
    }

    private static String cmd(String command, String text) {
        return "<qqbot-cmd-input text=\"%s\" show=\"%s\" reference=\"false\" />".formatted(command, text);
    }

    private static String at(String id) {
        return "<qqbot-at-user id=\"%s\" /> ".formatted(id);
    }

    public PendingMessage boMessage(Response<?> response) {
        return PendingMessage.ofMarkdownRaw(
                "> b" + response.getScoreIds().size() + "查询完成\n" +
                        "玩家: " + response.getUserId(),
                Buttons.boButtons()
        );
    }

    public PendingMessage rsMessage(Response<?> response) {
        return PendingMessage.ofMarkdownRaw(
                "> 最近成绩查询完成\n" +
                        "玩家: " + response.getUserId() + "\n数量: " + response.getScoreIds().size(),
                Buttons.rsButtons()
        );
    }

    public PendingMessage beatmapMessage(Response<?> response) {
        return PendingMessage.ofMarkdownRaw(
                "> 铺面查询完成\n" +
                        "铺面: " + cmd("/m " + response.getBeatmapId(), response.getBeatmapId()) + "\n" +
                        "铺面集: " + cmd("/ms m" + response.getBeatmapId(), response.getBeatmapsetId()),
                Buttons.beatmapButtons(response.getBeatmapId(), config.seira().directUrl())
        );

    }

    public PendingMessage scoreMessage(Response<?> response) {
        return PendingMessage.ofMarkdownRaw(
                "> 成绩查询完成\n" +
                        "铺面: " + cmd("/m " + response.getBeatmapId(), response.getBeatmapId()) + "\n" +
                        "成绩: " + cmd("/s " + response.getScoreId(), response.getScoreId()),
                Buttons.sButtons(response.getBeatmapId(), response.getScoreId())
        );
    }

    public PendingMessage lbMessage(Response<?> response) {
        return PendingMessage.ofMarkdownRaw(
                "> 排行榜查询完成" + (response.getBeatmapId() == null ? "" : "\n铺面: " + cmd("/m " + response.getBeatmapId(), response.getBeatmapId())),
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

    public PendingMessage replayStatMessage(String jobId, RenderStat renderStat) {
        return PendingMessage.ofMarkdownRaw(
                Contents.replayStatContent(renderStat, jobId),
                Buttons.replayProgressButtons(jobId)
        );
    }

    public PendingMessage searchMessage(Response<List<SearchResultItem>> response, SearchQuery searchQuery) {
        int SEARCH_ITEMS_PER_PAGE = 10;
        return PendingMessage.ofMarkdownRaw(
                Contents.searchContent(response, searchQuery, SEARCH_ITEMS_PER_PAGE),
                Buttons.searchButtons(response, searchQuery, SEARCH_ITEMS_PER_PAGE)
        );
    }

    public PendingMessage beatmapsetMessage(Response<?> response) {
        return PendingMessage.ofMarkdownRaw(
                Contents.beatmapsetContent(response),
                null
        );
    }

    public PendingMessage bindMessage(BindingConfig config, BindingHelper.BindingTask task, boolean isC2C) {
        final String url = "https://osu.ppy.sh/oauth/authorize?client_id=%d&response_type=code&scope=public+identify+friends.read&state=%s"
                .formatted(config.clientId(), task.taskId());
        return PendingMessage.ofMarkdownRaw(
                (isC2C ? "" : at(task.openId())) + "点击下方按钮绑定账号,或者在浏览器打开以下链接: \n```\n%s\n```".formatted(url),
                Buttons.bindButtons(task.openId(), url, !isC2C)
        );
    }

    public PendingMessage testMessage() {
        return PendingMessage.ofMarkdownRaw(
                """
                        > 这是一个测试消息
                        """,
                null
        );
    }

    public PendingMessage inspectMessage(String senderUserId, boolean isAdmin, String groupId, String messageId) {
        return PendingMessage.ofMarkdownRaw(
                """
                        用户ID%s:
                        ```text
                        %s
                        ```
                        
                        群组ID:
                        ```text
                        %s
                        ```
                        
                        消息ID:
                        ```text
                        %s
                        ```
                        """.formatted(isAdmin ? "(管理员)" : "", senderUserId, groupId, messageId),
                null
        );
    }

    private static final class Contents {
        static String replayStatContent(RenderStat renderStat, String jobId) {
            StringBuilder sb = new StringBuilder();
            sb.append("> 请求: ").append(jobId, 0, 8).append("\n");

            sb.append("状态: ").append(switch (renderStat.getStatus()) {
                case "done" -> "已完成";
                case "failed" -> "失败";
                case "timeout" -> "超时";
                case "queued" -> "排队中";
                case "rendering" -> "渲染中";
                default -> "未知";
            }).append("\n");

            if (Objects.equals("rendering", renderStat.getStatus())) {
                sb.append("进度: ").append(renderStat.getProgress() == null ? "未知" : renderStat.getProgress()).append("\n");
                sb.append("速度: ").append(renderStat.getSpeed() == null ? "未知" : renderStat.getSpeed()).append("\n");
                sb.append("预计时间: ").append(renderStat.getEta() == null ? "未知" : renderStat.getEta()).append("\n");
            }

            return sb.toString().trim();
        }

        static String searchContent(Response<List<SearchResultItem>> response, SearchQuery query, int itemsPerPage) {
            final List<SearchResultItem> items = response.getContent();

            if (items.size() <= (query.page() - 1) * itemsPerPage) {
                return "没有找到更多的搜索结果了哦~";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("\uD83D\uDD0D").append("搜索结果 - `").append(query.query()).append("`\n");

            for (int i = (query.page() - 1) * itemsPerPage; i < Math.min(items.size(), query.page() * itemsPerPage); i++) {
                SearchResultItem item = items.get(i);
                sb.append("> ");
                sb.append(i + 1).append("# ")
                        .append(cmd("/ms " + item.beatmapsetId(), String.valueOf(item.beatmapsetId())))
                        .append(" - ").append(item.artist()).append(" - ").append(item.title())
                        .append(" <").append(item.mapperName()).append("> ").append(String.format("[%.2f★ ~ %.2f★]", item.minStar(), item.maxStar())).append("\n");
            }

            return sb.toString();
        }

        static String beatmapsetContent(Response<?> response) {
            StringBuilder sb = new StringBuilder();
            sb.append("> 铺面集查询完成").append("\n");
            sb.append("> 铺面集: ").append(cmd("/ms " + response.getBeatmapsetId(), response.getBeatmapsetId())).append("\n");
            sb.append("> ");
            for (int i = 0; i < response.getBeatmapStars().size(); i++) {
                sb.append(cmd("/m " + response.getBeatmapIds().get(i), response.getBeatmapStars().get(i) + "★")).append(" ");
            }
            return sb.toString().trim();
        }
    }

    private static final class Buttons {
        static List<List<Button>> bindButtons(String userId, String url, boolean restrict) {
            final Button button = Button.openUrl(1, "登录", url);
            if (restrict) button.permit(userId);
            return Button.keyboard(Button.row(button));
        }

        static List<List<Button>> searchButtons(Response<List<SearchResultItem>> response, SearchQuery query, int itemsPerPage) {
            final List<String> ids = response.getBeatmapsetIds();
            if (ids == null || ids.isEmpty()) {
                return null;
            }

            List<List<Button>> rows = new ArrayList<>();

            List<Button> navRow = new ArrayList<>(3);

            if (query.page() > 1) {
                navRow.add(Button.command(1, "上一页", "/sms #" + (query.page() - 1) + " " + query.query()));
            } else {
                navRow.add(Button.command(1, false, "上一页", ""));
            }

            final String label = query.page() + "/" + ((int) Math.ceil(response.getBeatmapsetIds().size() / (double) itemsPerPage));
            navRow.add(Button.command(2, false, label, "/sms #" + query.page() + " " + query.query()));

            if (query.page() * itemsPerPage < ids.size()) {
                navRow.add(Button.command(3, "下一页", "/sms #" + (query.page() + 1) + " " + query.query()));
            } else {
                navRow.add(Button.command(3, false, "下一页", ""));
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

        private static void fillDummyButtons(List<Button> buttons) {
            final int c = 5 - buttons.size();
            for (int i = 0; i < c; i++) {
                buttons.add(Button.command(i + 100, false, "", ""));
            }
        }
    }
}

