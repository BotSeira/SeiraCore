package xyz.zcraft.seira.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import xyz.zcraft.osu.model.*;
import xyz.zcraft.seira.api.APIHelper;
import xyz.zcraft.seira.api.Response;
import xyz.zcraft.seira.binding.BindingHelper;
import xyz.zcraft.seira.config.AppConfig;
import xyz.zcraft.seira.config.BindingConfig;
import xyz.zcraft.seira.data.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

final class ReplyFactory {
    private final Buttons buttons;

    ReplyFactory(AppConfig config) {
        this.buttons = new Buttons(config.seira().directUrl());
    }

    static String cmd(String command, String text) {
        return "<qqbot-cmd-input text=\"%s\" show=\"%s\" reference=\"false\" />".formatted(command, text);
    }

    static String at(Context ctx) {
        if (ctx.inGroup()) {
            return "<qqbot-at-user id=\"%s\" /> ".formatted(ctx.senderUserId());
        } else {
            return "";
        }
    }

    static String url(String text, String url) {
        return "[" + text + "](" + url + ")";
    }

    public PendingMessage boMessage(Context ctx, Response<?> response) {
        return PendingMessage.ofMarkdownRaw(
                at(ctx) + "B" + response.getScoreIds().size() + "查询完成\n" +
                        "> 玩家: " + cmd("/u " + response.getUserId(), response.getUserId()),
                buttons.boButtons(response.getUserId())
        );
    }

    public PendingMessage rsMessage(Context ctx, Response<?> response) {
        return PendingMessage.ofMarkdownRaw(
                at(ctx) + "最近成绩查询完成\n" +
                        "> 玩家: " + cmd("/u " + response.getUserId(), response.getUserId()) + "\n" +
                        "> 数量: " + response.getScoreIds().size(),
                buttons.rsButtons()
        );
    }

    public PendingMessage beatmapMessage(Context ctx, Response<?> response) {
        return PendingMessage.ofMarkdownRaw(
                at(ctx) + "谱面查询完成\n" +
                        "> 谱面: " + cmd("/m " + response.getBeatmapId(), response.getBeatmapId()) + "\n" +
                        "> 谱面集: " + cmd("/ms m" + response.getBeatmapId(), response.getBeatmapsetId()),
                buttons.beatmapButtons(response.getBeatmapId())
        );

    }

    public PendingMessage scoreMessage(Context ctx, Response<?> response) {
        return PendingMessage.ofMarkdownRaw(
                at(ctx) + "成绩查询完成\n" +
                        "> 谱面: " + cmd("/m " + response.getBeatmapId(), response.getBeatmapId()) + "\n" +
                        "> 成绩: " + cmd("/s " + response.getScoreId(), response.getScoreId()),
                buttons.sButtons(response.getBeatmapId(), response.getScoreId())
        );
    }

    public PendingMessage scoreAnalyzeMessage(Context ctx, Response<?> response) {
        return PendingMessage.ofMarkdownRaw(
                at(ctx) + "成绩分析完成\n" +
                        "> 谱面: " + cmd("/m " + response.getBeatmapId(), response.getBeatmapId()) + "\n" +
                        "> 成绩: " + cmd("/s " + response.getScoreId(), response.getScoreId()),
                buttons.sButtons(response.getBeatmapId(), response.getScoreId())
        );
    }

    public PendingMessage lbMessage(Context ctx, Response<?> response) {
        return PendingMessage.ofMarkdownRaw(
                at(ctx) + "排行榜查询完成" +
                        (response.getBeatmapId() == null ? "" : "\n> 谱面: " + cmd("/m " + response.getBeatmapId(), response.getBeatmapId())),
                buttons.lbButtons(response.getBeatmapId())
        );
    }

    public PendingMessage replayMessage(Context ctx, APIHelper.ReplayTaskInfo taskInfo) {
        return PendingMessage.ofMarkdownRaw(
                Contents.replayTaskContent(ctx, taskInfo),
                buttons.replayProgressButtons(taskInfo.taskId())
        );
    }

    public PendingMessage replayStatMessage(Context ctx, String jobId, RenderStat renderStat) {
        return PendingMessage.ofMarkdownRaw(
                Contents.replayStatContent(ctx, renderStat, jobId),
                buttons.replayProgressButtons(jobId)
        );
    }

    public PendingMessage searchMessage(Context ctx, Response<List<SearchResultItem>> response, SearchQuery searchQuery) {
        int SEARCH_ITEMS_PER_PAGE = 10;
        return PendingMessage.ofMarkdownRaw(
                Contents.searchContent(ctx, response, searchQuery, SEARCH_ITEMS_PER_PAGE),
                buttons.searchButtons(response, searchQuery, SEARCH_ITEMS_PER_PAGE)
        );
    }

    public PendingMessage beatmapsetMessage(Context ctx, Response<?> response) {
        return PendingMessage.ofMarkdownRaw(
                Contents.beatmapsetContent(ctx, response),
                null
        );
    }

    public PendingMessage dlMessage(Context ctx, Response<?> response) {
        return PendingMessage.ofMarkdownRaw(
                at(ctx) + "\n" +
                        "> 谱面集: " + response.getBeatmapsetId() + "\n" +
                        "> 选择下载镜像: ",
                buttons.dlButton(response.getBeatmapsetId())
        );
    }

    public PendingMessage bindMessage(Context ctx, BindingConfig config, BindingHelper.BindingTask task, boolean isC2C) {
        final String url = "https://osu.ppy.sh/oauth/authorize?client_id=%d&response_type=code&scope=public+identify+friends.read&state=%s"
                .formatted(config.clientId(), task.taskId());
        return PendingMessage.ofMarkdownRaw(
                at(ctx) + "点击下方按钮绑定账号,或者在浏览器打开以下链接: \n```\n%s\n```".formatted(url),
                buttons.bindButtons(task.openId(), url, !isC2C)
        );
    }

    public PendingMessage mpMessage(Context ctx, Response<MultiplayerRoom> response) {
        return PendingMessage.ofMarkdownRaw(
                Contents.mpContent(ctx, response.getContent()),
                buttons.mpButtons(response.getContent())
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

    public PendingMessage inspectMessage(Context ctx, String senderUserId, boolean isAdmin, String groupId, String messageId) {
        return PendingMessage.ofMarkdownRaw(
                at(ctx) + "\n" +
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

    public PendingMessage friendMessage(Context ctx, UserExtended self, int followedCount,
                                        List<User> mutual, List<User> onlyFollowed, List<User> onlyFollower) {
        return PendingMessage.ofMarkdownRaw(
                Contents.friendContent(ctx, self, followedCount, mutual, onlyFollowed, onlyFollower),
                null
        );
    }

    private static final class Contents {
        static String replayTaskContent(Context ctx, APIHelper.ReplayTaskInfo taskInfo) {
            StringBuilder sb = new StringBuilder();
            sb.append(at(ctx)).append("回放生成请求已提交").append("\n");

            if (taskInfo.beatmap() != null) {
                BeatmapExtended beatmap = taskInfo.beatmap();

                sb.append("> 谱面: ");
                sb.append("%s - %s - %s [%.2f★ %s]".formatted(
                        cmd("/m " + beatmap.getId(), String.valueOf(beatmap.getId())),
                        beatmap.getBeatmapset().getArtist(),
                        beatmap.getBeatmapset().getTitle(),
                        beatmap.getDifficultyRating(),
                        beatmap.getVersion()
                ));
                sb.append("\n");
            }

            if (taskInfo.scores() != null) {
                JsonArray scores = taskInfo.scores();
                sb.append("> 共 %d 个成绩:".formatted(scores.size()));

                for (JsonElement element : scores) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    String line = buildScoreLine(element.getAsJsonObject());
                    if (line == null) {
                        continue;
                    }
                    if (!sb.isEmpty()) {
                        sb.append("\n");
                    }
                    sb.append(line);
                }
            }

            return sb.toString().trim();
        }

        private static String buildScoreLine(JsonObject score) {
            String username = getScoreField(score, "username");
            String rank = getScoreField(score, "rank");
            String accuracy = getScoreField(score, "accuracy");
            String pp = getScoreField(score, "pp");
            String id = getScoreField(score, "id");

            if (username == null && rank == null && accuracy == null && pp == null) {
                return null;
            }

            return cmd("/s " + id, "%s / %s / %s / %s".formatted(id, rank, accuracy, pp));
        }

        private static String getScoreField(JsonObject score, String field) {
            if (score == null || !score.has(field) || score.get(field).isJsonNull()) {
                return null;
            }
            try {
                return score.get(field).getAsString();
            } catch (Exception ignored) {
                return null;
            }
        }

        static String replayStatContent(Context ctx, RenderStat renderStat, String jobId) {
            StringBuilder sb = new StringBuilder();
            sb.append(at(ctx)).append("\n");
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

        static String searchContent(Context ctx, Response<List<SearchResultItem>> response, SearchQuery query, int itemsPerPage) {
            final List<SearchResultItem> items = response.getContent();

            if (items.size() <= (query.page() - 1) * itemsPerPage) {
                return at(ctx) + "没有找到更多的搜索结果了哦~";
            }

            StringBuilder sb = new StringBuilder();
            sb.append(at(ctx));
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

        static String beatmapsetContent(Context ctx, Response<?> response) {
            StringBuilder sb = new StringBuilder();
            sb.append(at(ctx)).append("谱面集查询完成").append("\n");
            sb.append("> 谱面集: ").append(cmd("/ms " + response.getBeatmapsetId(), response.getBeatmapsetId())).append("\n");
            sb.append("> ");
            for (int i = 0; i < response.getBeatmapStars().size(); i++) {
                sb.append(cmd("/m " + response.getBeatmapIds().get(i), response.getBeatmapStars().get(i) + "★")).append(" ");
            }
            return sb.toString().trim();
        }

        public static String friendContent(Context ctx, UserExtended self, int followedCount,
                                           List<User> mutual, List<User> onlyFollowed, List<User> onlyFollower) {
            StringBuilder sb = new StringBuilder();
            sb.append(at(ctx));
            if (ctx.inGroup()) {
                sb.append("\uD83D\uDC65").append("本群好友列表");
            } else {
                sb.append("\uD83D\uDC65").append("全部好友列表 - 共 ").append(followedCount);
            }

            final long onlineCount = Stream.of(mutual, onlyFollowed, onlyFollower)
                    .flatMap(List::stream)
                    .distinct()
                    .filter(User::isOnline)
                    .count();

            sb.append(" - ").append(onlineCount).append(" 在线").append("\n");

            sb.append("\n");

            sb.append("> 好友←→ (").append(mutual.size()).append(")\n>");
            for (User p : mutual) {
                sb.append(getFriendItem(p)).append(" ");
            }

            sb.append("\n> 仅关注→ (").append(onlyFollowed.size()).append(")\n>");
            for (User p : onlyFollowed) {
                sb.append(getFriendItem(p)).append(" ");
            }

            sb.append("\n> 仅粉丝← (").append(onlyFollower.size()).append(" 已知 共").append(self.getFollowerCount()).append(")\n>");
            for (User p : onlyFollower) {
                sb.append(getFriendItem(p)).append(" ");
            }

            return sb.toString().trim();
        }

        private static String getFriendItem(User u) {
            return cmd("/u " + u.getId(), "[" + (u.isOnline() ? "▶" : "") + u.getUsername() + "]");
        }

        public static String mpContent(Context ctx, MultiplayerRoom content) {
            String sb = at(ctx) + "进行中的多人游戏" + "\n" +
                    "> 房间名: " + content.getName() + "\n" +
                    "> 人数: " + content.getParticipantCount() + "\n" +
                    "> ID: " + content.getId() + "\n";
            final MultiplayerRoom.CurrentPlaylistItem cur = content.getCurrentPlaylistItem();
            if (cur != null) {
                sb += "> 当前: " + "%s - %s - %s [%.2f★ %s]".formatted(
                        cmd("/m " + cur.getBeatmapId(), String.valueOf(cur.getBeatmapId())),
                        cur.getBeatmap().getBeatmapset().getArtist(),
                        cur.getBeatmap().getBeatmapset().getTitle(),
                        cur.getBeatmap().getDifficultyRating(),
                        cur.getBeatmap().getVersion()
                ) + "\n";
            }
            sb += "加入房间或下载谱面:";
            return sb.trim();
        }
    }

    private record Buttons(String directUrl) {
        List<List<Button>> mpButtons(MultiplayerRoom room) {
            return Button.keyboard(
                    Button.row(
                            room.isHasPassword()
                                    ? Button.openUrl(1, "房间未公开", null).disable()
                                    : Button.openUrl(1, "加入房间", directUrl + "/room/" + room.getId())
                    )
                    , Optional.ofNullable(room.getCurrentPlaylistItem())
                            .map(MultiplayerRoom.CurrentPlaylistItem::getBeatmap)
                            .map(Beatmap::getBeatmapsetId)
                            .map(i -> dlButtonRow(String.valueOf(i)))
                            .orElse(null)
                    , Optional.ofNullable(room.getCurrentPlaylistItem())
                            .map(MultiplayerRoom.CurrentPlaylistItem::getBeatmap)
                            .map(Beatmap::getBeatmapsetId)
                            .map(i -> dlButtonRowSecond(String.valueOf(i)))
                            .orElse(null)
            );
        }

        List<List<Button>> bindButtons(String userId, String url, boolean restrict) {
            final Button button = Button.openUrl(1, "登录", url);
            if (restrict) button.permit(userId);
            return Button.keyboard(Button.row(button));
        }

        List<List<Button>> searchButtons(Response<List<SearchResultItem>> response, SearchQuery query, int itemsPerPage) {
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

        List<List<Button>> boButtons(String userId) {
            return Button.keyboard(
                    Button.row(
                            Button.command(1, "查询最好成绩", "/s bo1"),
                            Button.command(2, "查询最近成绩", "/s rs1")
                    ),
                    Button.row(
                            Button.openUrl(3, "在游戏中查看", directUrl + "/u/" + userId)
                    )
            );
        }

        List<List<Button>> rsButtons() {
            return Button.keyboard(Button.row(
                    Button.command(1, "查询最好成绩", "/s bo1"),
                    Button.command(2, "查询最近成绩", "/s rs1")
            ));
        }

        List<List<Button>> sButtons(String beatmapId, String scoreId) {
            if (beatmapId == null || beatmapId.isBlank()) {
                return null;
            }

            return Button.keyboard(
                    Button.row(
                            Button.openUrl(1, "查看谱面", directUrl + "/b/" + beatmapId),
                            Button.command(2, "查询谱面", "/m " + beatmapId),
                            Button.command(3, "查询谱面集", "/ms m" + beatmapId)
                    ),
                    Button.row(
                            Button.command(4, "成绩分析", "/sa " + scoreId),
                            Button.command(5, "查询排行", "/lb " + beatmapId),
                            Button.command(6, "渲染回放", "/r " + scoreId)
                    )
            );
        }

        List<List<Button>> lbButtons(String beatmapId) {
            if (beatmapId == null || beatmapId.isBlank()) {
                return null;
            }

            return Button.keyboard(Button.row(
                    Button.command(1, "渲染同屏回放", "/rsc " + beatmapId)
            ));
        }

        List<List<Button>> replayProgressButtons(String jobId) {
            if (jobId == null || jobId.isBlank()) {
                return null;
            }

            return Button.keyboard(Button.row(
                    Button.command(1, "查询渲染进度", "/rstat " + jobId)
            ));
        }

        List<List<Button>> beatmapButtons(String beatmapId) {
            if (beatmapId == null || beatmapId.isBlank()) {
                return null;
            }

            return Button.keyboard(
                    Button.row(
                            Button.command(1, "查询排行榜", "/lb " + beatmapId),
                            Button.openUrl(2, "在游戏中查看", directUrl + "/b/" + beatmapId)
                    ),
                    Button.row(Button.command(3, "查询自己的分数", "/s m" + beatmapId))
            );
        }

        public List<List<Button>> dlButton(String beatmapsetId) {
            return Button.keyboard(
                    dlButtonRow(beatmapsetId),
                    dlButtonRowSecond(beatmapsetId)
            );
        }

        @NotNull
        private List<Button> dlButtonRow(String beatmapsetId) {
            return Button.row(
                    Button.openUrl(101, "官网", "https://osu.ppy.sh/beatmapsets/" + beatmapsetId + "/download"),
                    Button.openUrl(102, "Sayobot", "https://dl.sayobot.cn/beatmaps/download/" + beatmapsetId),
                    Button.openUrl(103, "Nekoha", "https://mirror.nekoha.moe/api4/download/" + beatmapsetId)
            );
        }

        @NotNull
        private List<Button> dlButtonRowSecond(String beatmapsetId) {
            return Button.row(
                    Button.openUrl(104, "Nerinyan", "https://api.nerinyan.moe/d/" + beatmapsetId),
                    Button.openUrl(105, "Hinamizawa", "https://mirror.hinamizawa.ai/d/" + beatmapsetId)
            );
        }
    }
}

