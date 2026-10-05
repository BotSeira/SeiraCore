package xyz.zcraft.seira.command.reply;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import xyz.zcraft.osu.model.*;
import xyz.zcraft.osu.model.multiplayer.Room;
import xyz.zcraft.seira.api.AsteroidApi;
import xyz.zcraft.seira.api.OstellaApi;
import xyz.zcraft.seira.api.data.*;
import xyz.zcraft.seira.bot.data.Button;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.config.AppConfig;
import xyz.zcraft.seira.config.BindingConfig;
import xyz.zcraft.seira.data.UploadedImage;
import xyz.zcraft.seira.db.RankGuessRecordStore;
import xyz.zcraft.seira.db.UserDataStore;
import xyz.zcraft.seira.rankguess.data.*;
import xyz.zcraft.seira.services.BindingService;
import xyz.zcraft.seira.services.BotStat;
import xyz.zcraft.seira.services.DailyLuck;
import xyz.zcraft.seira.util.VersionInfo;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static xyz.zcraft.seira.command.reply.ReplyFactory.ExternalUrls.*;

public final class ReplyFactory {
    private final Supplier<AppConfig> configSupplier;

    public ReplyFactory(AppConfig config) {
        this(() -> config);
    }

    public ReplyFactory(Supplier<AppConfig> configSupplier) {
        this.configSupplier = Objects.requireNonNull(configSupplier);
    }

    public static String cmd(String command, String text) {
        return "<qqbot-cmd-input text=\"%s\" show=\"%s\" reference=\"false\" />".formatted(command, text);
    }

    public static String cmd(String command) {
        return cmd(command, command);
    }

    public static String m(String id) {
        return cmd("/m " + id, id);
    }

    public static String m(long id) {
        return cmd("/m " + id, String.valueOf(id));
    }

    public static String s(String id) {
        return cmd("/s " + id, id);
    }

    public static String s(long id) {
        return cmd("/s " + id, String.valueOf(id));
    }

    public static String ms(String id) {
        return cmd("/ms " + id, id);
    }

    public static String ms(long id) {
        return cmd("/ms " + id, String.valueOf(id));
    }

    @SuppressWarnings("unused")
    public static String u(String id, String name) {
        return cmd("/u " + id, name);
    }

    public static String u(long id, String name) {
        return cmd("/u " + id, name);
    }

    public static String u(long id) {
        return cmd("/u " + id, String.valueOf(id));
    }

    public static String u(String id) {
        return cmd("/u " + id, id);
    }

    public static String at(Context ctx) {
        if (ctx.inGroup()) {
            return at(ctx.senderUserId());
        } else {
            return "";
        }
    }

    public static String at(String openId) {
        return "<qqbot-at-user id=\"%s\" /> ".formatted(openId);
    }

    @SuppressWarnings("unused")
    public static String url(String text, String url) {
        return "[" + text + "](" + url + ")";
    }

    public static PendingMessage replayUploadMessage(ReplayUploadInfo info) {
        return PendingMessage.ofMarkdownRaw(
                ("\n" + "__Replay上传成功~__" + "\n" +
                        "> 成绩: " + s(info.scoreId()) + "\n" +
                        "> 谱面: " + m(info.beatmapId()) + "\n" +
                        "> 用户: " + u(info.userId(), info.username()) + "\n").trim(),
                null
        );
    }

    private static boolean isCancelableReplayStatus(String status) {
        return "queued".equals(status) || "rendering".equals(status)
                || "upload_queued".equals(status) || "uploading".equals(status);
    }

    public PendingMessage friendStatusMessage(String selfOpenId, Long selfUid, String selfOsuAvatar, String selfUsername, String selfAvatar,
                                              String targetOpenId, Long targetUid, String targetOsuAvatar, String targetUsername, String targetAvatar,
                                              boolean selfFollowed, Boolean targetFollowed) {
        return PendingMessage.ofMarkdownRaw(
                Contents.friendStatusContent(
                        selfOpenId, selfUid, selfOsuAvatar, selfUsername, selfAvatar,
                        targetOpenId, targetUid, targetOsuAvatar, targetUsername, targetAvatar,
                        selfFollowed, targetFollowed
                )
        );
    }

    private Buttons buttons() {
        return new Buttons(getDirectUrl());
    }

    private String getDirectUrl() {
        return configSupplier.get().seira().directUrl();
    }

    public PendingMessage rankGuessResultMessage(Context ctx, FinishedRound result, EndResult.RankType rankType) {
        Round round = result.round();
        String rank = String.format(Locale.US, "%,d", round.actualRank());
        String pp = round.pp() == null
                ? "未知"
                : "%.1f".formatted(round.pp()) + "pp";

        String userAt = UserDataStore.findGroupOpenIdByUid(ctx.groupId(), round.userId())
                .map(e -> "(" + at(e) + ")")
                .orElse("");

        StringBuilder content = new StringBuilder("本轮猜测结束");

        if (rankType == EndResult.RankType.RANKED) {
            content.append("，战绩已记录");
        } else if (rankType == EndResult.RankType.NOT_ENOUGH_PARTICIPANT) {
            content.append("，由于参与人数过少，战绩不会记录");
        } else if (rankType == EndResult.RankType.NOT_A_STANDARD_GAME) {
            content.append("，由于无主动消息权限，非完整游戏，战绩不会记录");
        }

        content.append("~\n");

        content.append("> 玩家：`%s` %s\n".formatted(round.randomScore().user().getUsername(), userAt))
                .append("> 实际Rank：`#%s` (%s)\n".formatted(rank, u(round.userId())))
                .append("> 成绩：`%s` (%s|%s)\n"
                        .formatted(
                                pp,
                                "BP" + round.randomScore().bestIndex(),
                                s(round.scoreId())
                        ))
                .append("\n猜测排行榜：\n");

        if (result.standings().isEmpty()) {
            content.append("> （暂无猜测）");
        } else {
            for (int i = 0; i < result.standings().size(); i++) {
                Standing standing = result.standings().get(i);
                content.append(
                        "> %d. %s: %,dpts(x%.2f) #%,d(%+,d/%+.2f%%)\n"
                                .formatted(
                                        i + 1,
                                        at(standing.senderUserId()),
                                        Math.round(standing.points()),
                                        standing.multiplier(),
                                        standing.guess(),
                                        standing.delta(),
                                        (standing.delta() / (double) round.actualRank()) * 100.00
                                )
                );
            }
        }

        return PendingMessage.ofMarkdownRaw(content.toString().trim());
    }

    public PendingMessage rankGuessStatisticsMessage(
            Context ctx, String ref, RankGuessRecordStore.Statistics.Personal statistics,
            RankGuessRecordStore.Statistics.Personal recentStatistics,
            boolean allGroups, Rank rank,
            Long pickedTimes, Long groupGameCount,
            RankGuessRecordStore.RankGuessed rankGuessed,
            Long gameStarted
    ) {
        String scope = allGroups ? "全部群聊" : "本群";
        if (statistics.participation() == 0) {
            return PendingMessage.ofMarkdownRaw(at(ctx) + ref + "在" + scope + "还没有已结算的猜 Rank 战绩喵~");
        }

        String rankText = "?".equals(rank.rank()) ? "" : "根据" + ref + "最近 %d 场的表现，可以给到一个 `%s` 喵！\n"
                .formatted(Rank.RECENT_GAME_LIMIT, rank.rank());
        String groupCountText = "";
        String averageGuessedText = "";
        String gameStartedText = "";
        if (!allGroups && pickedTimes != null && groupGameCount != null) {
            groupCountText = "> 被猜次数：`%d`，占本群：`%.3f%%`\n".formatted(pickedTimes, (double) pickedTimes / groupGameCount * 100);
        }
        if (!allGroups && rankGuessed != null) {
            averageGuessedText = "> 平均被猜为：`#%,d` / `#%,d`\n".formatted((long) rankGuessed.average(), (long) rankGuessed.logAverage());
        }
        if (!allGroups && gameStarted != null) {
            gameStartedText = "> 在本群发起了 `%d` 场游戏\n".formatted(gameStarted);
        }
        return PendingMessage.ofMarkdownRaw(at(ctx) + String.format(Locale.ROOT, """
                        %s的猜 Rank 战绩（%s，括号为近 %d 场）
                        > 总参与数：`%d`，Rating：`%.2f`
                        > 获胜数：`%d`（`%d`）
                        > 胜率：`%.2f%%`（`%.2f%%`）
                        > 前20%%：`%d`（`%d`）
                        > 前20%%达成率：`%.2f%%`（`%.2f%%`）
                        > 平均分：`%.2f`（`%.2f`）
                        > 最高分：`%.2f`（`%.2f`）
                        > 平均名次：`%.2f`（`%.2f`）
                        > 总得分：`%.2f`
                        %s%s%s%s
                        """,
                ref, scope, Rank.RECENT_GAME_LIMIT,
                statistics.participation(), rank.rating(),
                statistics.wins(), recentStatistics.wins(),
                statistics.winRate() * 100, recentStatistics.winRate() * 100,
                statistics.topTwentyCount(), recentStatistics.topTwentyCount(),
                statistics.topTwentyRate() * 100, recentStatistics.topTwentyRate() * 100,
                statistics.averageScore(), recentStatistics.averageScore(),
                statistics.highestScore(), recentStatistics.highestScore(),
                statistics.averagePlacement(), recentStatistics.averagePlacement(),
                statistics.totalScore(), gameStartedText, averageGuessedText, groupCountText, rankText).strip());
    }

    public PendingMessage bpMessage(Context ctx, Response<?> response) {
        return PendingMessage.ofMarkdownRaw(
                at(ctx) + "查询完成，共" + response.getScoreIds().size() + "个成绩\n" +
                        "> 玩家: " + u(response.getUserId()),
                buttons().bpButtons(response.getUserId())
        );
    }

    public PendingMessage rsMessage(Context ctx, Response<?> response) {
        return PendingMessage.ofMarkdownRaw(
                at(ctx) + "最近成绩查询完成\n" +
                        "> 玩家: " + u(response.getUserId()) + "\n" +
                        "> 数量: " + response.getScoreIds().size(),
                buttons().rsButtons()
        );
    }

    public PendingMessage userInfoMessage(Context ctx, Response<?> response) {
        return PendingMessage.ofMarkdownRaw(
                at(ctx) + "玩家资料查询完成\n" +
                        "> 玩家: " + u(response.getUserId()),
                buttons().userInfoButtons(response.getUserId())
        );
    }

    public PendingMessage tbMessage(Context ctx, Response<?> response) {
        return PendingMessage.ofMarkdownRaw(
                at(ctx) + "近日BP查询完成\n" +
                        "> 玩家: " + u(response.getUserId()) + "\n" +
                        "> 数量: " + response.getScoreIds().size(),
                buttons().bpButtons(response.getUserId())
        );
    }

    public PendingMessage beatmapMessage(Context ctx, Response<?> response) {
        return PendingMessage.ofMarkdownRaw(
                at(ctx) + "谱面查询完成\n" +
                        "> 谱面: " + m(response.getBeatmapId()) + "\n" +
                        "> 谱面集: " + ms(response.getBeatmapsetId()),
                buttons().beatmapButtons(response.getBeatmapId())
        );

    }

    public PendingMessage scoreMessage(Context ctx, Response<?> response) {
        return PendingMessage.ofMarkdownRaw(
                at(ctx) + "成绩查询完成\n" +
                        "> 谱面: " + m(response.getBeatmapId()) + "\n" +
                        "> 成绩: " + s(response.getScoreId()),
                buttons().sButtons(response.getBeatmapId(), response.getScoreId())
        );
    }

    public PendingMessage scoreAnalyzeMessage(Context ctx, Response<?> response) {
        return PendingMessage.ofMarkdownRaw(
                at(ctx) + "成绩分析完成\n" +
                        "> 谱面: " + m(response.getBeatmapId()) + "\n" +
                        "> 成绩: " + s(response.getScoreId()),
                buttons().saButtons(response.getBeatmapId(), response.getScoreId())
        );
    }

    public PendingMessage lbMessage(Context ctx, Response<?> response) {
        return PendingMessage.ofMarkdownRaw(
                at(ctx) + "排行榜查询完成" +
                        (response.getBeatmapId() == null ? "" : "\n> 谱面: " + m(response.getBeatmapId())),
                buttons().lbButtons(response.getBeatmapId())
        );
    }

    public PendingMessage replayMessage(Context ctx, OstellaApi.ReplayTaskInfo taskInfo) {
        return PendingMessage.ofMarkdownRaw(
                Contents.replayTaskContent(ctx, taskInfo),
                buttons().replayProgressButtons(taskInfo.taskId(), ctx.senderUserId())
        );
    }

    public PendingMessage replayStatMessage(Context ctx, String jobId, RenderStat renderStat) {
        return PendingMessage.ofMarkdownRaw(
                Contents.replayStatContent(ctx, renderStat, jobId),
                buttons().replayProgressButtons(jobId, isCancelableReplayStatus(renderStat.getStatus()), ctx.senderUserId())
        );
    }

    public PendingMessage searchMessage(Context ctx, Response<List<SearchResultItem>> response, SearchQuery searchQuery) {
        int SEARCH_ITEMS_PER_PAGE = 10;
        return PendingMessage.ofMarkdownRaw(
                Contents.searchContent(ctx, response, searchQuery, SEARCH_ITEMS_PER_PAGE),
                buttons().searchButtons(response, searchQuery, SEARCH_ITEMS_PER_PAGE)
        );
    }

    public PendingMessage beatmapsetMessage(Context ctx, Response<?> response) {
        return PendingMessage.ofMarkdownRaw(
                Contents.beatmapsetContent(ctx, response),
                buttons().beatmapsetButtons(response.getBeatmapsetId())
        );
    }

    public PendingMessage dlMessage(Context ctx, Response<?> response) {
        return PendingMessage.ofMarkdownRaw(
                at(ctx) + "\n" +
                        "> 谱面集: " + response.getBeatmapsetId() + "\n" +
                        "> 选择下载镜像: ",
                buttons().dlButton(response.getBeatmapsetId())
        );
    }

    public PendingMessage bindMessage(Context ctx, BindingConfig config, BindingService.BindingTask task, boolean isC2C) {
        final String url = "https://osu.ppy.sh/oauth/authorize?client_id=%d&response_type=code&scope=public+identify+friends.read&state=%s"
                .formatted(config.clientId(), task.taskId());
        return PendingMessage.ofMarkdownRaw(
                at(ctx) + "点击下方按钮绑定账号,或者在浏览器打开以下链接: \n```\n%s\n```\n> 绑定请求20分钟内有效。".formatted(url),
                buttons().bindButtons(task.openId(), url, !isC2C)
        );
    }

    public PendingMessage mpMessage(Context ctx, Response<Room> response) {
        return PendingMessage.ofMarkdownRaw(
                Contents.mpContent(ctx, response.getContent()),
                buttons().mpButtons(response.getContent())
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

    public PendingMessage friendMessage(Context ctx,
                                        boolean all,
                                        UserExtended self,
                                        int allFollowedCount,
                                        long allMutualCount,
                                        List<User> mutual,
                                        List<User> onlyFollowed,
                                        List<User> onlyFollower) {
        return PendingMessage.ofMarkdownRaw(
                Contents.friendContent(ctx, all, self, allFollowedCount, allMutualCount, mutual, onlyFollowed, onlyFollower),
                null
        );
    }

    public PendingMessage scoreMissesMessage(Context ctx, Response<List<MissData>> scoreMissesResponse) {
        return PendingMessage.ofMarkdownRaw(
                Contents.scoreMissesContent(ctx, scoreMissesResponse),
                null
        );
    }

    public PendingMessage replayClipMessage(Context ctx, String scoreId, String position) {
        return PendingMessage.ofMarkdownRaw(at(ctx) + "\n > 回放动图：" + position + "\n> 成绩 " + s(scoreId));
    }

    public PendingMessage snapshotImageMessage(Context ctx, String scoreId, String position) {
        return PendingMessage.ofMarkdownRaw(at(ctx) + "\n > 回放快照：" + position + "\n> 成绩 " + s(scoreId)
                + "\n> " + cmd("/ma " + scoreId, "查看 Miss 列表"));
    }

    public PendingMessage statusMessage(Context ctx, OstellaApi.ServerStatus status, AsteroidApi.ServerStatus asteroid) {
        return PendingMessage.ofMarkdownRaw(
                Contents.statContent(ctx, status, asteroid), null
        );
    }

    public PendingMessage helpMessage(Context ctx) {
        return PendingMessage.ofMarkdownRaw(Contents.helpContent(ctx));
    }

    public PendingMessage usagesMessage(Context ctx) {
        return PendingMessage.ofMarkdownRaw(Contents.usagesContent(ctx));
    }

    public PendingMessage faqMessage(Context ctx) {
        return PendingMessage.ofMarkdownRaw(Contents.faqContent(ctx));
    }

    public PendingMessage bgpMessage(Context context, Response<?> response) {
        return PendingMessage.ofMarkdownRaw(
                Contents.bgpContent(context, response),
                null
        );
    }

    public PendingMessage luckMessage(Context ctx, DailyLuck.Luck luck, Beatmapset mapset, UploadedImage cover) {
        return PendingMessage.ofMarkdownRaw(
                Contents.luckContent(ctx, luck, mapset, cover),
                null
        );
    }

    public PendingMessage missImageMessage(Context ctx, String scoreId, Integer index, int size) {
        return PendingMessage.ofMarkdownRaw(
                Contents.missImageContent(ctx, scoreId, index, size),
                Buttons.missImageButton(ctx, scoreId, index, size)
        );
    }

    public PendingMessage supMessage(Context ctx, String username, String openId, Boolean isSupporter, Boolean hasSupported, Integer supportLevel) {
        return PendingMessage.ofMarkdownRaw(Contents.supContent(ctx, username, openId, isSupporter, hasSupported, supportLevel));
    }

    public PendingMessage userInfoShortMessage(Context ctx, UserExtended user) {
        return PendingMessage.ofMarkdownRaw(Contents.userInfoShortContent(ctx, user));
    }

    public static class ExternalUrls {
        public static final String COMMANDS = "https://docs.seira.top/overview/commands.html";
        public static final String PERMISSION = "https://docs.seira.top/overview/use.html#extra-permission";
        public static final String CHANNEL = "https://pd.qq.com/s/f9icas5gj?b=5";
        public static final String CHANGELOG = "https://docs.seira.top/overview/changelog.html";
        public static final String FAQ = "https://docs.seira.top/overview/faq.html";
        public static final String GITHUB = "https://github.com/BotSeira";
    }

    private static final class Contents {
        static String replayTaskContent(Context ctx, OstellaApi.ReplayTaskInfo taskInfo) {
            StringBuilder sb = new StringBuilder();
            sb.append(at(ctx)).append("回放生成请求已提交").append("\n");

            if (taskInfo.beatmap() != null) {
                BeatmapExtended beatmap = taskInfo.beatmap();

                sb.append("> 谱面: ").append(m(beatmap.getId())).append("\n");
                sb.append("> ").append(beatmap.getBeatmapset().getArtist()).append(" - ").append(beatmap.getBeatmapset().getTitle()).append("\n");
                sb.append("> ").append(String.format("%.2f★", beatmap.getDifficultyRating())).append(" ").append(beatmap.getVersion()).append("\n");
            }

            if (taskInfo.start() != null || taskInfo.end() != null) {
                sb.append("> 时间: ");
                if (taskInfo.start() != null) {
                    Duration start = Duration.of(taskInfo.start().longValue(), ChronoUnit.SECONDS);
                    sb.append("从 ").append("%02d:%02d".formatted(start.toMinutesPart(), start.toSecondsPart())).append(" ");
                }
                if (taskInfo.end() != null) {
                    Duration end = Duration.of(taskInfo.end().longValue(), ChronoUnit.SECONDS);
                    sb.append("到 ").append("%02d:%02d".formatted(end.toMinutesPart(), end.toSecondsPart()));
                }
                sb.append("\n");
            }

            if (taskInfo.mods() != null) {
                sb.append("> Mod: ").append(taskInfo.mods()).append("\n");
            }
            if (taskInfo.selection() != null) {
                String selection = switch (taskInfo.selection()) {
                    case "kiai" -> "Kiai 段";
                    case "high-pressure" -> "高压段";
                    case "full-map" -> "完整可用片段";
                    default -> taskInfo.selection();
                };
                sb.append("> 选段: ").append(selection).append("\n");
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
                    sb.append("\n").append(line);
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

            return "> - %s - %s \n (%s %s %s)".formatted(s(id), username, rank, accuracy, pp);
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
                case "upload_queued" -> "等待上传";
                case "uploading" -> "上传中";
                case "canceled" -> "已取消";
                default -> "未知";
            }).append("\n");

            if (Objects.equals("rendering", renderStat.getStatus())) {
                sb.append("进度: ").append(renderStat.getProgress() == null ? "未知" : renderStat.getProgress()).append("\n");
                sb.append("速度: ").append(renderStat.getSpeed() == null ? "未知" : renderStat.getSpeed()).append("\n");
                sb.append("预计时间: ").append(renderStat.getEta() == null ? "未知" : renderStat.getEta()).append("\n");
            }
            if (renderStat.getError() != null && !renderStat.getError().isBlank()) {
                sb.append("原因: ").append(renderStat.getError()).append("\n");
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
            sb.append("> 谱面集: ").append(ms(response.getBeatmapsetId())).append("\n");
            sb.append("> ");
            for (int i = 0; i < response.getBeatmapStars().size(); i++) {
                sb.append(cmd("/m " + response.getBeatmapIds().get(i), response.getBeatmapStars().get(i) + "★")).append(" ");
            }
            return sb.toString().trim();
        }

        public static String friendContent(Context ctx,
                                           boolean all,
                                           UserExtended self,
                                           int followedCount,
                                           long allMutualCount,
                                           List<User> mutual,
                                           List<User> onlyFollowed,
                                           List<User> onlyFollower) {
            StringBuilder sb = new StringBuilder();
            sb.append(at(ctx));
            if (ctx.inGroup() && !all) {
                sb.append("\uD83D\uDC65").append("本群好友列表 - 共 ")
                        .append(
                                Stream.of(mutual, onlyFollowed)
                                        .flatMap(List::stream)
                                        .distinct()
                                        .count()
                        );
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

            boolean collapsed = false;

            sb.append("> __好友←→ (").append(mutual.size()).append(")__ \n>");
            collapsed |= appendFriends(ctx, mutual, sb);

            sb.append("\n> __仅关注→ (").append(onlyFollowed.size()).append(")__ \n>");
            collapsed |= appendFriends(ctx, onlyFollowed, sb);

            sb.append("\n> __仅粉丝← (");
            sb.append(onlyFollower.size()).append(" 已知");
            if (all) sb.append(" 共 ").append(Math.max(self.getFollowerCount() - allMutualCount, 0));
            sb.append(")__ \n>");
            collapsed |= appendFriends(ctx, onlyFollower, sb);

            if (ctx.inGroup() && collapsed) {
                sb.append("\n部分结果已折叠，如需查看完整结果请在私聊中使用指令~");
            }

            return sb.toString().trim();
        }

        private static boolean appendFriends(Context ctx, List<User> onlyFollowed, StringBuilder sb) {
            int count = 0;
            for (User p : onlyFollowed) {
                if (count >= 20 && ctx.inGroup()) {
                    sb.append("\n...剩余").append(onlyFollowed.size() - count).append("个");
                    return true;
                }
                sb.append(getFriendItem(p)).append(" ");
                count++;
            }
            return false;
        }

        private static String getFriendItem(User u) {
            return cmd("/u " + u.getId(), "[" + (u.isOnline() ? "▶" : "") + u.getUsername() + "]");
        }

        public static String mpContent(Context ctx, Room content) {
            String sb = at(ctx) + "进行中的多人游戏" + "\n" +
                    "> 房间名: " + content.getName() + "\n" +
                    "> 人数: " + content.getParticipantCount() + "\n" +
                    "> ID: " + content.getId() + "\n";
            final Room.PlaylistItem cur = content.getCurrentPlaylistItem();
            if (cur != null) {
                sb += "> 当前: " + "%s - %s - %s [%.2f★ %s]".formatted(
                        m(cur.getBeatmapId()),
                        cur.getBeatmap().getBeatmapset().getArtist(),
                        cur.getBeatmap().getBeatmapset().getTitle(),
                        cur.getBeatmap().getDifficultyRating(),
                        cur.getBeatmap().getVersion()
                ) + "\n";
            }
            sb += "加入房间或下载谱面:";
            return sb.trim();
        }

        public static String scoreMissesContent(Context ctx, Response<List<MissData>> scoreMissesResponse) {
            final List<MissData> content = scoreMissesResponse.getContent();
            if (content.isEmpty()) {
                return at(ctx) + "本成绩没有 Miss~";
            }

            StringBuilder sb = new StringBuilder();
            sb.append(at(ctx)).append("成绩 Miss 列表 (共 ").append(content.size()).append(" )\n");
            for (int i = 0; i < Math.min(10, content.size()); i++) {
                final MissData cur = content.get(i);
                final Duration time = Duration.of(cur.time(), ChronoUnit.MILLIS);
                sb.append("> ").append(cmd("/ma " + scoreMissesResponse.getScoreId() + " " + cur.index(), "#" + cur.index()))
                        .append(" - ").append("%02d:%02d.%03d".formatted(time.toMinutesPart(), time.toSecondsPart(), time.toMillisPart()))
                        .append(" - ").append(cur.type().toString()).append("\n");
            }
            if (content.size() > 10) {
                sb.append("...剩余 ").append(content.size() - 10).append(" 个").append("\n");
            }

            return sb.toString().trim();
        }

        public static String statContent(Context ctx, OstellaApi.ServerStatus status, AsteroidApi.ServerStatus asteroid) {
            String stat = at(ctx) + "\n" +
                    "## 服务器状态\n" +
                    "> 消息网关: ✅ 正常\n" +
                    "> oStella API: " + (status.oStella() ? "✅ 正常" : "❌ 无法访问") + "\n";

            if (status.oStella()) {
                stat += "> ↳ osuRenderer: " + (status.onlineWorkers() + " / " + status.allWorkers())
                        + (status.onlineWorkers() > 0 ? " (✅在线)" : " (❌全部离线)") + "\n";
                stat += "> ↳ osu! API: " + (status.osu() ? "✅ 正常" : "❌ 无法访问") + "\n";
            }

            stat += "> Asteroid API: " + (asteroid.online() ? "✅ 正常" : "❌ 无法访问") + "\n";

            String version = "## 版本信息" + "\n"
                    + "> SeiraCore: " + VersionInfo.getVersion() + "\n";

            if (status.oStella() && status.oStellaVersion() != null) {
                version += "> oStella: " + status.oStellaVersion() + "\n";
            }

            if (asteroid.online() && asteroid.version() != null) {
                version += "> Asteroid: " + asteroid.version() + "\n";
            }


            String res = "## 统计信息\n" +
                    "> Seira已经" + "\n" +
                    "> - 总共运行了 `" + BotStat.getTotalUptime() / 1000 / 60 + "` 分钟" + "\n" +
                    "> - 连续运行了 `" + BotStat.getCurrentUptime() / 1000 / 60 + "` 分钟" + "\n" +
                    "> - 总共处理了 `" + BotStat.getTotalCommands() + "` 条指令" + "(近30分钟 `" + BotStat.getCommandCountFor(30) + "` )\n" +
                    "> - 总共渲染了 `" + BotStat.getTotalReplays() + "` 条回放" + "\n" +
                    "> - 总共进行了 `" + RankGuessRecordStore.getTotalGamesCount(null) + "` 次猜 Rank" + "\n" +
                    "> - 并正在为 `" + UserDataStore.countGroups() + "` 个群聊和 `" + UserDataStore.countBoundUser() + "` 位用户提供服务~" + "\n";
            return (stat + version + res).trim();
        }

        public static String helpContent(Context ctx) {
            return at(ctx) + "常用指令: \n" + """
                    > /rp - 获取最近通过的一个或多个成绩
                    > /bp - 获取一个或多个最佳成绩
                    > /tb - 获取近日BP
                    > /s - 获取指定成绩
                    > /m - 获取谱面
                    > /r - 生成成绩高光视频或指定片段
                    > /rg - 猜 Rank 游戏
                    > /whatif - 估算总 PP 与全球排名的对应关系
                    > /addpp - 估算新增成绩后的总 PP 与排名变化
                    > /rep - 生成指定时刻周围的 GIF，范围最多 6 秒
                    > /snap - 生成指定时间、物件或 Miss 的回放快照
                    > /watch - 监视群友的新成绩
                    > /gch - 群挑战，自选难度，按水平调整成绩
                    > /mpw <MPLink> - 监视多人房间的逐图结果
                    > /f - 获取好友列表
                    
                    [详细指令列表](%s) | [配置额外权限](%s)
                    [加入官方频道](%s) | [查看常见问题](%s)
                    [查看更新日志](%s) | %s
                    %s | [Github主页](%s)
                    
                    当前版本: %s
                    """.formatted(
                    COMMANDS, PERMISSION,
                    CHANNEL, FAQ,
                    CHANGELOG, cmd("/stat", "查看状态信息"),
                    cmd("/usages", "查看用法示例"), GITHUB,
                    VersionInfo.getVersion()
            ) + "\n";
        }

        public static String usagesContent(Context ctx) {
            return at(ctx) + "部分指令示例\n" +
                    "> 注意：所有指令中的@均需要开启权限才能正常读取。权限配置见 [这里]( " + PERMISSION + " )~\n" + """
                    > /rp -> 查看最近通过的一个成绩
                    > /whatif 12345pp -> 估算总 PP 对应的全球排名
                    > /addpp [user] 200*4 -> 估算新增4条200pp成绩后的总 PP 与排名
                    > /addpp [user] m1234567 HDDT 98% FC -> 估算指定谱面成绩加入 BP 后的变化
                    > /addpp [user] rp2 HD 98% -> 用第2条最近通过成绩的谱面估算 PP
                    > /snap rp1 01:23.456 -> 最近通过成绩在指定歌曲时间的快照
                    > /snap #3 -50ms -> 最近查询成绩的第3个Miss前50ms的快照
                    > /whatif #12345 -> 估算全球排名所需的总 PP（纯数字也按排名解析）
                    > /rp1-20 -> 查看最近通过的1到20个成绩
                    > /bp1-20 -> 查看20个最佳成绩
                    > /bp1-20 @peppy acc>95 -> 查看指定玩家BP1-20中准确率大于95%的成绩
                    > /sa bp2 -> 查看BP2的成绩分析
                    > /tb #7 @peppy -> 查看指定玩家近7天的新BP
                    > /@peppy -> 查看指定玩家的基本信息
                    > /rg group -> 开始群组猜 Rank 游戏
                    > /gch start 12345 24 -> 开始24小时的自选难度群挑战
                    > /gch join -> 加入本群挑战
                    > /m @peppy rp2 -> 查看指定玩家最近第2条成绩的谱面
                    > /dl mp -> 获取所在lazer多人房间当前谱面的镜像下载链接
                    > /m mp123456 -> 查询指定lazer多人房间的当前谱面
                    > /dl mp123456 -> 下载指定lazer多人房间当前谱面的谱面集
                    > /s mp123456 -> 查询自己在该房间当前谱面的成绩
                    > /r rp -> 渲染最近通过的成绩的高光片段回放视频
                    > /r @peppy bp2 90- -> 渲染指定玩家BP2从1:30开始的回放视频
                    > /mpw <mplink> -> 开始多人房间监视
                    > /romai @peppy -> 开始监视指定玩家所在的RomAI对局
                    """;
        }

        public static String faqContent(Context ctx) {
            return at(ctx) + "\n" + """
                    常见问题请在 [这里](https://docs.seira.top/overview/faq.html) 查看
                    """.trim();
        }

        public static String bgpContent(Context context, Response<?> response) {
            return at(context) + "\n> 背景预览(" + ms(response.getBeatmapsetId()) + " - " + m(response.getBeatmapId()) + ")";
        }

        public static String luckContent(Context ctx, DailyLuck.Luck luck, Beatmapset mapset, UploadedImage cover) {
            final List<Double> list = mapset.getBeatmaps().stream().map(Beatmap::getDifficultyRating).sorted().toList();
            String sb = at(ctx) + "你的今日运势" + "\n" +
                    "> 人品值: **" + luck.luck() + "**/100\n" +
                    "> 宜: " + luck.ups() + "\n" +
                    "> 忌: " + luck.downs() + "\n\n" +
                    "今日推荐图: " + ms(mapset.getId()) + "\n" +
                    "> %s - %s [★%.2f-★%.2f]".formatted(mapset.getArtist(), mapset.getTitle(), list.getFirst(), list.getLast()) + "\n" +
                    ">" + cover.toMarkdown();
            return sb.trim();
        }

        public static String friendStatusContent(String selfOpenId, Long selfUid, String selfOsuAvatar, String selfUsername, String selfAvatar,
                                                 String targetOpenId, Long targetUid, String targetOsuAvatar, String targetUsername, String targetAvatar,
                                                 boolean selfFollowed, Boolean targetFollowed) {
            final String status;
            if (targetFollowed == null) {
                if (selfFollowed) {
                    status = "? 未知 ↓";
                } else {
                    status = "? 未知 ✕";
                }
            } else {
                if (selfFollowed && targetFollowed) {
                    status = "↑ 好友 ↓";
                } else if (selfFollowed) {
                    status = "✕ 单向 ↓";
                } else if (targetFollowed) {
                    status = "↑ 单向 ✕";
                } else {
                    status = "✕ 路人 ✕";
                }
            }

            return """
                    %s 和 %s 的好友状态:
                    > # ![image #30px #30px](%s)&ensp;__|__&ensp;%s
                    
                    # %s
                    
                    > # ![image #30px #30px](%s)&ensp;__|__&ensp;%s
                    """.formatted(
                    at(selfOpenId), targetAvatar == null ? targetUsername : at(targetOpenId),
                    selfOsuAvatar, url(selfUsername, "https://osu.ppy.sh/users/" + selfUid),
                    status,
                    targetOsuAvatar, url(targetUsername, "https://osu.ppy.sh/users/" + targetUid)
            ).trim();
        }

        public static String missImageContent(Context ctx, String scoreId, Integer index, int size) {
            return at(ctx) + s(scoreId) + " - " + "Miss#" + index + "/" + size;
        }

        public static String supContent(
                Context ctx, String username, String openId, Boolean isSupporter, Boolean hasSupported, Integer supportLevel
        ) {
            final StringBuilder sb = new StringBuilder();
            sb.append(at(ctx)).append("当前 ").append("`%s`".formatted(username));
            if (openId != null) {
                sb.append("(%s)".formatted(at(openId)));
            }
            sb.append(" 的支持者状态:\n");
            if (isSupporter != null) {
                if (isSupporter) {
                    sb.append("> - √ 是撒泼特");
                } else {
                    sb.append("> - × 不是撒泼特");
                }
                sb.append("\n");
            }
            if (hasSupported != null) {
                if (hasSupported) {
                    sb.append("> - √ 有支持历史");
                } else {
                    sb.append("> - × 无支持历史");
                }
                sb.append("\n");
            }
            if (supportLevel != null) {
                sb.append("> - 支持者等级: `%d`".formatted(supportLevel));
                sb.append("\n");
            }

            return sb.toString().trim();
        }

        public static String userInfoShortContent(Context ctx, UserExtended user) {
            final Duration playTime = Duration.ofSeconds(user.getStatistics().getPlayTime());
            return """
                    %s `%s` 的用户信息
                    > - PP: %.2f
                    > - Rank: #%,d (%s #%,d)
                    > - 准确率: %.2f%%
                    > - 游玩次数: %,d
                    > - 获得总分: %,d
                    > - 游玩时间: %dd %dh %dm
                    """.formatted(
                    at(ctx), user.getUsername(),
                    user.getStatistics().getPp(),
                    user.getStatistics().getGlobalRank(), user.getCountry().getCode(), user.getStatistics().getRank().getCountry(),
                    user.getStatistics().getAccuracy() * 100,
                    user.getStatistics().getPlayCount(),
                    user.getStatistics().getRankedScore(),
                    playTime.toDaysPart(), playTime.toHoursPart(), playTime.toMinutesPart()
            );
        }
    }

    private record Buttons(String directUrl) {
        public static List<List<Button>> missImageButton(Context ctx, String scoreId, Integer index, int size) {
            List<Button> row = new ArrayList<>(3);
            final Button prev = Button.command(1, "上一个", "/ma " + scoreId + " " + (index - 1));
            if (index <= 1) {
                prev.disable();
            }

            final Button center = Button.command(2, index + "/" + size, "").disable();

            final Button next = Button.command(3, "下一个", "/ma " + scoreId + " " + (index + 1));
            if (index >= size) {
                next.disable();
            }

            row.addAll(List.of(prev, center, next));

            final Button repButton = Button.command(4, "查看动图", "/rep " + scoreId + " #" + index);
            return Button.keyboard(row, List.of(
                    repButton
            ));
        }

        List<List<Button>> beatmapsetButtons(String beatmapsetId) {
            if (beatmapsetId == null || beatmapsetId.isBlank()) {
                return null;
            }

            return Button.keyboard(
                    Button.row(
                            Button.command(1, "预览音频", "/ap " + beatmapsetId),
                            Button.openUrl(2, "在游戏中查看", directUrl + "/s/" + beatmapsetId)
                    )
            );
        }

        List<List<Button>> mpButtons(Room room) {
            List<List<Button>> rows = new ArrayList<>();

            rows.add(Button.row(
                    room.isHasPassword()
                            ? Button.openUrl(1, "房间未公开", null).disable()
                            : Button.openUrl(1, "加入房间", directUrl + "/room/" + room.getId())
            ));

            Optional.ofNullable(room.getCurrentPlaylistItem())
                    .map(Room.PlaylistItem::getBeatmap)
                    .map(Beatmap::getBeatmapsetId)
                    .map(String::valueOf)
                    .ifPresent(id -> {
                        rows.add(dlButtonRow(id));
                        rows.add(dlButtonRowSecond(id));
                    });

            return List.copyOf(rows);
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

        List<List<Button>> bpButtons(String userId) {
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

        List<List<Button>> userInfoButtons(String userId) {
            return Button.keyboard(
                    Button.row(
                            Button.command(1, "查询最好成绩", "/bo 5 " + userId),
                            Button.command(2, "查询最近成绩", "/rs 5 " + userId)
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
                            Button.command(6, "渲染高光", "/r " + scoreId)
                    )
            );
        }

        List<List<Button>> saButtons(String beatmapId, String scoreId) {
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
                            Button.command(4, "Misses", "/ma " + scoreId),
                            Button.command(5, "查询排行", "/lb " + beatmapId),
                            Button.command(6, "渲染高光", "/r " + scoreId)
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

        List<List<Button>> replayProgressButtons(String jobId, String userId) {
            return replayProgressButtons(jobId, true, userId);
        }

        List<List<Button>> replayProgressButtons(String jobId, boolean cancelable, String userId) {
            if (jobId == null || jobId.isBlank()) {
                return null;
            }

            return Button.keyboard(cancelable
                    ? Button.row(
                    Button.command(1, "查询渲染进度", "/rstat " + jobId),
                    Button.command(2, "取消渲染", "/rcancel " + jobId)
                            .permit(userId)
                            .modal("确定要取消渲染吗")
            )
                    : Button.row(Button.command(1, "查询渲染进度", "/rstat " + jobId)));
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
                    Button.row(
                            Button.command(3, "预览音频", "/ap m" + beatmapId),
                            Button.command(4, "查询自己的分数", "/s m" + beatmapId)
                    ),
                    Button.row(
                            Button.command(5, "预览谱面", "/bpv " + beatmapId),
                            Button.command(6, "分析谱面", "/bma " + beatmapId)
                    )
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

