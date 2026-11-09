package xyz.zcraft.seira.command.handler;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import xyz.zcraft.osu.model.Beatmapset;
import xyz.zcraft.seira.api.AsteroidApi;
import xyz.zcraft.seira.api.OstellaApi;
import xyz.zcraft.seira.api.data.ncm.Artist;
import xyz.zcraft.seira.api.data.ncm.MatchResult;
import xyz.zcraft.seira.api.data.ncm.Playlist;
import xyz.zcraft.seira.api.data.ncm.Song;
import xyz.zcraft.seira.bot.MessageSender;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.command.TargetHistory;
import xyz.zcraft.seira.command.TaskCoordinator;
import xyz.zcraft.seira.command.parse.Resolver;
import xyz.zcraft.seira.command.parse.TargetInput;
import xyz.zcraft.seira.command.parse.TargetResolver;
import xyz.zcraft.seira.command.reply.ReplyFactory;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static xyz.zcraft.seira.command.reply.ReplyFactory.*;

public class NcmHandler {
    private static final Logger LOG = LogManager.getLogger(NcmHandler.class);
    private static final Pattern TITLE_PATTERN = Pattern.compile("^([^()]+)((?:\\(.+\\))+)$");
    private final MessageSender messageSender;
    private final TargetResolver targets;
    private final TargetHistory history;
    private final TaskCoordinator taskCoordinator;
    private final ReplyFactory replyFactory;
    private final Resolver resolver;
    private final Predicate<String> adminAuthorizer;

    public NcmHandler(
            MessageSender messageSender,
            TaskCoordinator taskCoordinator,
            ReplyFactory replyFactory,
            Resolver resolver,
            TargetResolver targets,
            TargetHistory history,
            Predicate<String> adminAuthorizer
    ) {
        this.messageSender = messageSender;
        this.taskCoordinator = taskCoordinator;
        this.replyFactory = replyFactory;
        this.resolver = resolver;
        this.targets = targets;
        this.history = history;
        this.adminAuthorizer = adminAuthorizer;
    }

    public void handleNcm(Context ctx) {
        var target = ctx.argumentCount() == 0 ? TargetInput.memory() : TargetInput.read(ctx.args());
        var remembered = history.get(ctx);
        if ((target.kind() == TargetInput.Kind.MEMORY && remembered == null)
                || ctx.argumentCount() - target.consumedArgs() > 0) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "用法：/ncm <谱面集ID 或 快捷查询>"));
            return;
        }
        try (var _ = taskCoordinator.beginRequest(ctx, "Beatmapset")) {
            var ids = targets.beatmapset(ctx, target, remembered);
            history.remember(ctx, ids);
            final Beatmapset beatmapset = OstellaApi.getBeatmapsetRaw(ids.beatmapsetId());

            ctx.sendReply(
                    at(ctx) + "正在网易云音乐搜索 `%s` 喵..."
                            .formatted(beatmapset.getTitleUnicode() + " - " + beatmapset.getArtistUnicode())
            );

            StringBuilder sb = new StringBuilder(at(ctx) + "网易云音乐搜索结果:\n");

            final List<MatchResult> matchResults = AsteroidApi.matchSong(ids.beatmapsetId());

            final Set<Long> listedIds = new HashSet<>();

            if (matchResults.isEmpty()) {
                sb.append("听歌识曲未找到结果喵。\n");
            } else {
                sb.append("| 来源: | 听歌识曲 | 网易云 |\n|:---|:---|:---:|\n");
                for (int i = 0; i < Math.min(matchResults.size(), 5); i++) {
                    MatchResult result = matchResults.get(i);
                    final Matcher matcher = TITLE_PATTERN.matcher(result.song().name());
                    String title;
                    String subtitle;
                    if (matcher.matches() && matcher.group(2) != null && !matcher.group(2).isEmpty()) {
                        title = matcher.group(1);
                        subtitle = matcher.group(2);
                    } else {
                        title = result.song().name();
                        subtitle = "";
                    }
                    sb.append("| ![Cover #80px #80px](%s) | $\\begin{array}{l}\\large{\\textbf{%s}}%s\\\\\\small{\\textsf{%s}}\\\\\\tiny{\\textsf{%s}}\\end{array}$ | [打开](https://music.163.com/song?id=%d) |".formatted(
                            result.song().album().picUrl(),
                            escape(title),
                            !subtitle.isEmpty() ? "\\\\\\small{\\textit{%s}}".formatted(escape(subtitle)) : "",
                            escape("By: " + result.song().artists().stream().map(Artist::name).collect(Collectors.joining(", "))),
                            escape("专辑: " + result.song().album().name()),
                            result.song().id()
                    )).append("\n");
                    listedIds.add(result.song().id());
                }
            }

            sb.append("\n");

            final List<Song> searchResults = AsteroidApi.searchSong(beatmapset.getTitleUnicode() + " " + beatmapset.getArtistUnicode());

            if (searchResults.isEmpty()) {
                sb.append("搜索未找到结果喵。\n");
            } else {
                sb.append("| 来源: 搜索 | 网易云 |\n|:---|:---:|\n");
                for (int i = 0; i < Math.min(searchResults.size(), 2); i++) {
                    Song result = searchResults.get(i);
                    if (listedIds.contains(result.id())) continue;

                    sb.append("| $\\begin{array}{l}\\large{\\textbf{%s}}\\\\\\small{\\textsf{%s}}\\\\\\tiny{\\textsf{%s}}\\end{array}$ | [打开](https://music.163.com/song?id=%d) |".formatted(
                            escape(result.name()),
                            escape("By: " + result.artists().stream().map(Artist::name).collect(Collectors.joining(", "))),
                            escape("专辑: " + result.album().name()),
                            result.id()
                    )).append("\n");
                }
            }

            ctx.sendReply(sb.toString().trim());
        }
    }

    public String escape(String text) {
        return text.replaceAll("([\\\\$%#&_])", "\\\\$1");
    }

    public void handleNcmPlaylist(Context ctx) {
        final String usage = "用法: /ncmp <歌单ID> [Start-End]";

        PlaylistQuery playlistQuery = requirePlaylistQuery(ctx, usage);
        if (playlistQuery == null) return;

        final List<OstellaApi.BatchSearchResult> results = OstellaApi.searchBeatmapSetBatch(playlistQuery.queries());

        StringBuilder sb = new StringBuilder(at(ctx));

        sb.append(" 歌单 `").append(playlistQuery.playlistDetail().name()).append("` 的 ").append(playlistQuery.offset() + 1).append(" - ").append(playlistQuery.offset() + playlistQuery.limit()).append(" 首歌曲:\n");

        sb.append("| 标题 | 作者 | 匹配ID |\n").append("|:--:|:--:|:--:|\n");

        for (var result : results) {
            if (result.status().equalsIgnoreCase("no_match")) {
                continue;
            }

            String matchedSets = result.matches()
                    .stream()
                    .sorted(Comparator.comparing(OstellaApi.BatchSearchResult.Match::score).reversed())
                    .map(match -> ms(match.beatmapset().id()) + (match.score() == 1 ? "" : "(" + (int) (match.score() * 100) + "%)"))
                    .limit(3).collect(Collectors.joining(","));

            if (!result.status().equalsIgnoreCase("matched")) {
                matchedSets = "(?)" + matchedSets;
            }

            sb.append("| %s | %s | %s |\n".formatted(result.title(), result.artist(), matchedSets));
        }

        final long noMatch = results.stream()
                .filter(r -> r.status().equalsIgnoreCase("no_match"))
                .count();

        sb.append("\n> %d / %d 首匹配 | %s"
                .formatted(
                        results.size() - noMatch,
                        results.size(),
                        cmd("/ncmu %d %d-%d".formatted(playlistQuery.playlistId(), playlistQuery.offset() + 1, playlistQuery.offset() + playlistQuery.limit()), "更新缓存")
                )
        );

        ctx.sendReply(sb.toString().trim());
    }

    @Nullable
    private PlaylistQuery requirePlaylistQuery(Context ctx, String usage) {
        if (ctx.argumentCount() == 0 || !ctx.argument(0).matches("\\d+")) {
            ctx.sendReply(at(ctx) + usage);
            return null;
        }

        long playlistId = Long.parseLong(ctx.argument(0));
        int limit = 50;
        int offset = 0;
        boolean providedRange = false;

        if (ctx.argumentCount() > 1) {
            String[] range = ctx.argument(1).split("-");
            if (range.length == 2) {
                try {
                    offset = Integer.parseInt(range[0]) - 1;
                    limit = Integer.parseInt(range[1]) - offset;
                    providedRange = true;
                    if (offset < 0 || limit <= 0) {
                        throw new IllegalArgumentException();
                    }
                } catch (Exception e) {
                    ctx.sendReply(at(ctx) + usage);
                    return null;
                }
            } else {
                ctx.sendReply(at(ctx) + usage);
                return null;
            }
        }

        final Playlist playlistDetail = AsteroidApi.getPlaylistDetail(playlistId);

        if (playlistDetail == null) {
            ctx.sendReply(at(ctx) + "获取歌单详情失败喵。");
            return null;
        }

        if (offset + limit > playlistDetail.trackCount() && providedRange) {
            ctx.sendReply(at(ctx) + "给定的范围 `%d-%d` 超出歌单范围(%d)了喵。".formatted(offset + 1, offset + limit, playlistDetail.trackCount()));
            return null;
        }

        limit = Math.toIntExact(Math.min(limit, playlistDetail.trackCount() - offset));

        ctx.sendReply(at(ctx) + "正在获取歌单 `%s` 的第 `%d-%d` 首歌曲...".formatted(playlistDetail.name(), offset + 1, offset + limit));

        List<Song> songs = AsteroidApi.getPlaylistSongs(playlistId, limit, offset);

        if (songs.isEmpty()) {
            ctx.sendReply(at(ctx) + "歌单为空或获取失败喵。");
            return null;
        }

        final List<OstellaApi.BatchSearchQuery> queries = songs.stream()
                .map(s -> new OstellaApi.BatchSearchQuery(
                        s.name(),
                        s.artists().stream().map(Artist::name).collect(Collectors.joining(", ")))
                ).toList();

        return new PlaylistQuery(playlistId, limit, offset, playlistDetail, queries);
    }

    public void handleNcmUpdate(Context ctx) {
        final String usage = "用法: /ncmu <歌单ID> [Start-End]";

        PlaylistQuery playlistQuery = requirePlaylistQuery(ctx, usage);
        if (playlistQuery == null) return;

        final int i = OstellaApi.enqueueMetaUpdate(playlistQuery.queries());

        ctx.sendReply(at(ctx) + "已提交 %d 个缓存更新任务喵。".formatted(i));
    }

    private record PlaylistQuery(long playlistId, int limit, int offset, Playlist playlistDetail,
                                 List<OstellaApi.BatchSearchQuery> queries) {
    }
}
