package xyz.zcraft.seira.command.reply;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import xyz.zcraft.osu.model.*;
import xyz.zcraft.seira.api.APIHelper;
import xyz.zcraft.seira.api.data.*;
import xyz.zcraft.seira.binding.BindingHelper;
import xyz.zcraft.seira.binding.UserDataStore;
import xyz.zcraft.seira.bot.data.Button;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.config.AppConfig;
import xyz.zcraft.seira.config.BindingConfig;
import xyz.zcraft.seira.data.UploadedImage;
import xyz.zcraft.seira.game.RankGuessGameService;
import xyz.zcraft.seira.services.BotStat;
import xyz.zcraft.seira.services.DailyLuck;
import xyz.zcraft.seira.util.VersionInfo;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Stream;

public final class ReplyFactory {
    private final Buttons buttons;

    public ReplyFactory(AppConfig config) {
        this.buttons = new Buttons(config.seira().directUrl());
    }

    static String cmd(String command, String text) {
        return "<qqbot-cmd-input text=\"%s\" show=\"%s\" reference=\"false\" />".formatted(command, text);
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
    static String url(String text, String url) {
        return "[" + text + "](" + url + ")";
    }

    public static PendingMessage replayUploadMessage(ReplayUploadInfo info) {
        return PendingMessage.ofMarkdownRaw("""
                                ### Replay上传成功~
                                > 成绩: %s
                                > 铺面: %s
                                > 用户: %s
                        """
                        .formatted(
                                cmd("/s " + info.scoreId(), String.valueOf(info.scoreId())),
                                cmd("/m " + info.beatmapId(), String.valueOf(info.beatmapId())),
                                cmd("/u " + info.userId(), info.username())
                        ),
                null
        );
    }

    public PendingMessage rankGuessResultMessage(RankGuessGameService.FinishedRound result) {
        RankGuessGameService.Round round = result.round();
        String rank = String.format(Locale.US, "%,d", round.actualRank());
        String pp = round.pp() == null
                ? "未知"
                : BigDecimal.valueOf(round.pp()).stripTrailingZeros().toPlainString() + "pp";

        StringBuilder content = new StringBuilder()
                .append("本轮猜测结束~\n")
                .append("> 实际Rank：`#%s` (%s)\n".formatted(rank, cmd("/u " + round.userId(), String.valueOf(round.userId()))))
                .append("> 成绩PP：`%s` (%s)\n".formatted(pp, cmd("/s " + round.scoreId(), String.valueOf(round.scoreId()))))
                .append("\n猜测排行榜：\n");

        if (result.standings().isEmpty()) {
            content.append("> （暂无猜测）");
        } else {
            for (int i = 0; i < result.standings().size(); i++) {
                RankGuessGameService.Standing standing = result.standings().get(i);
                content.append(
                        "> %d. %s: %,dpts #%,d(%+,d)\n"
                                .formatted(
                                        i + 1,
                                        at(standing.senderUserId()),
                                        Math.round(standing.points()),
                                        standing.guess(),
                                        standing.delta()
                                )
                );
            }
        }

        return PendingMessage.ofMarkdownRaw(content.toString().trim());
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
                        "> 谱面集: " + cmd("/ms " + response.getBeatmapsetId(), response.getBeatmapsetId()),
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
                buttons.saButtons(response.getBeatmapId(), response.getScoreId())
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
                buttons.beatmapsetButtons(response.getBeatmapsetId())
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
                at(ctx) + "点击下方按钮绑定账号,或者在浏览器打开以下链接: \n```\n%s\n```\n> 绑定请求20分钟内有效。".formatted(url),
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

    public PendingMessage statusMessage(Context ctx, APIHelper.ServerStatus status) {
        return PendingMessage.ofMarkdownRaw(
                Contents.statContent(ctx, status), null
        );
    }

    public PendingMessage helpMessage(Context ctx) {
        return PendingMessage.ofMarkdownRaw(Contents.helpContent(ctx));
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

    private static final class Contents {
        static String replayTaskContent(Context ctx, APIHelper.ReplayTaskInfo taskInfo) {
            StringBuilder sb = new StringBuilder();
            sb.append(at(ctx)).append("回放生成请求已提交").append("\n");

            if (taskInfo.beatmap() != null) {
                BeatmapExtended beatmap = taskInfo.beatmap();

                sb.append("> 谱面: ").append(cmd("/m " + beatmap.getId(), String.valueOf(beatmap.getId()))).append("\n");
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

            return "> - %s - %s \n (%s %s %s)".formatted(cmd("/s " + id, id), username, rank, accuracy, pp);
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

            sb.append("> 好友←→ (").append(mutual.size()).append(")\n>");
            for (User p : mutual) {
                sb.append(getFriendItem(p)).append(" ");
            }

            sb.append("\n> 仅关注→ (").append(onlyFollowed.size()).append(")\n>");
            for (User p : onlyFollowed) {
                sb.append(getFriendItem(p)).append(" ");
            }

            sb.append("\n> 仅粉丝← (");
            sb.append(onlyFollower.size()).append(" 已知");
            if (all) sb.append(" 共 ").append(self.getFollowerCount() - allMutualCount);
            sb.append(")\n>");

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

        public static String statContent(Context ctx, APIHelper.ServerStatus status) {
            String stat = at(ctx) + "\n" +
                    "## 服务器状态: \n" +
                    "> 消息网关: ✅ 正常\n" +
                    "> oStella API: " + (status.oStella() ? "✅ 正常" : "❌ 无法访问") + "\n";

            if (status.oStella()) {
                stat += "> osu! API: " + (status.osu() ? "✅ 正常" : "❌ 无法访问") + "\n";
            }

            String version = "## 版本信息" + "\n"
                    + "> SeiraCore: " + VersionInfo.getVersion() + "\n";

            if (status.oStella() && status.oStellaVersion() != null) {
                version += "> oStella: " + status.oStellaVersion() + "\n";
            }


            String res = "## 统计信息\n" +
                    "> Seira已经" + "\n" +
                    "> - 总共运行了 `" + BotStat.getTotalUptime() / 1000 / 60 + "` 分钟" + "\n" +
                    "> - 连续运行了 `" + BotStat.getCurrentUptime() / 1000 / 60 + "` 分钟" + "\n" +
                    "> - 总共处理了 `" + BotStat.getTotalCommands() + "` 条指令" + "(近30分钟 `" + BotStat.getCommandCountFor(30) + "` )\n" +
                    "> - 总共渲染了 `" + BotStat.getTotalReplays() + "` 条回放" + "\n" +
                    "> - 并正在为 `" + UserDataStore.countGroups() + "` 个群聊和 `" + UserDataStore.countBoundUser() + "` 位用户提供服务~" + "\n";
            return (stat + version + res).trim();
        }

        public static String helpContent(Context ctx) {
            return at(ctx) + "\n" +
                    """
                            常用指令：
                            > /bind - 绑定你的玩家ID
                            > /rp - 获取最近通过的一个成绩
                            > /bo [个数] [玩家ID] - 获取一个或多个最佳成绩
                            > /rp [个数] [玩家ID] - 获取最近通过一个或多个成绩
                            > /s <成绩ID或快捷查询> - 获取指定成绩
                            > /m <谱面ID或快捷查询> - 获取谱面
                            > /ms <谱面集ID或快捷查询> - 获取谱面集
                            > /r [成绩ID或快捷查询] [[mm:ss]-[mm:ss]] - 生成成绩高光视频或指定片段
                            > /rg <start/#Rank/end> - 在群聊中进行猜 Rank 游戏
                            > /lb <谱面ID> [玩家ID列表] - 获取指定谱面排行榜
                            > /watch add <玩家ID/用户名/@用户> [分钟] - 监视群友的新成绩
                            > /f - 获取好友列表
                            
                            详细指令列表请在 [这里](https://docs.seira.top/overview/commands.html) 查看
                            """ + "\n"
                    + "[常见问题](https://docs.seira.top/overview/faq.html)" + " " + cmd("/stat", "状态信息").trim();
        }

        public static String faqContent(Context ctx) {
            return at(ctx) + "\n" + """
                    常见问题请在 [这里](https://docs.seira.top/overview/faq.html) 查看
                    """.trim();
        }

        public static String bgpContent(Context context, Response<?> response) {
            return at(context) + "\n> 背景预览("
                    + cmd("/ms " + response.getBeatmapsetId(), response.getBeatmapsetId())
                    + " - "
                    + cmd("/m " + response.getBeatmapId(), response.getBeatmapId())
                    + ")";
        }

        public static String luckContent(Context ctx, DailyLuck.Luck luck, Beatmapset mapset, UploadedImage cover) {
            final List<Double> list = mapset.getBeatmaps().stream().map(Beatmap::getDifficultyRating).sorted().toList();
            String sb = at(ctx) + "\n" +
                    "## 今日运势" + "\n" +
                    "> 人品值: **" + luck.luck() + "**/100\n" +
                    "> 宜: " + luck.ups() + "\n" +
                    "> 忌: " + luck.downs() + "\n\n" +
                    "今日推荐图: " + cmd("/ms " + mapset.getId(), mapset.getId().toString()) + "\n" +
                    "> %s - %s [★%.2f-★%.2f]".formatted(mapset.getArtist(), mapset.getTitle(), list.getFirst(), list.getLast()) + "\n" +
                    ">" + cover.toMarkdown();
            return sb.trim();
        }
    }

    private record Buttons(String directUrl) {
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

        List<List<Button>> mpButtons(MultiplayerRoom room) {
            List<List<Button>> rows = new ArrayList<>();

            rows.add(Button.row(
                    room.isHasPassword()
                            ? Button.openUrl(1, "房间未公开", null).disable()
                            : Button.openUrl(1, "加入房间", directUrl + "/room/" + room.getId())
            ));

            Optional.ofNullable(room.getCurrentPlaylistItem())
                    .map(MultiplayerRoom.CurrentPlaylistItem::getBeatmap)
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
                    Button.row(
                            Button.command(3, "预览音频", "/ap m" + beatmapId),
                            Button.command(4, "查询自己的分数", "/s m" + beatmapId)
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

