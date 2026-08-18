package xyz.zcraft.seira.command.handler;

import xyz.zcraft.seira.api.APIHelper;
import xyz.zcraft.seira.api.data.Response;
import xyz.zcraft.seira.api.data.SearchQuery;
import xyz.zcraft.seira.api.data.SearchResultItem;
import xyz.zcraft.seira.api.data.VideoRenderRecord;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.*;
import xyz.zcraft.seira.command.parse.Resolver;
import xyz.zcraft.seira.command.reply.CommandUsage;
import xyz.zcraft.seira.command.reply.ReplyFactory;
import xyz.zcraft.seira.command.parse.ShortcutTarget;
import xyz.zcraft.seira.command.parse.TargetResolution;

import java.util.List;
import java.util.function.Function;

public final class BeatmapCommandHandler {
    private final Resolver resolver;
    private final TargetHistory lastTarget;
    private final TaskCoordinator taskCoordinator;
    private final ReplyFactory replyFactory;
    private final VideoRenderRecord videoRenderRecord;
    private final Function<String, String> accessTokenProvider;

    public BeatmapCommandHandler(
            Resolver resolver,
            TargetHistory targetHistory,
            TaskCoordinator taskCoordinator,
            ReplyFactory replyFactory,
            VideoRenderRecord videoRenderRecord,
            Function<String, String> accessTokenProvider
    ) {
        this.resolver = resolver;
        this.lastTarget = targetHistory;
        this.taskCoordinator = taskCoordinator;
        this.replyFactory = replyFactory;
        this.videoRenderRecord = videoRenderRecord;
        this.accessTokenProvider = accessTokenProvider;
    }

    public void handleDaily(Context ctx) {
        taskCoordinator.runApiRequest(ctx, "Daily Challenge", () ->
                ctx.sendReply(PendingMessage.ofMarkdownRaw(APIHelper.getDaily()))
        );
    }

    public void handleM(Context ctx) {
        if (ctx.args().length >= 1) {
            TargetResolution targetResolution = resolver.resolveTargetWithOptionalMention(ctx.args(), ctx.senderUserId());
            ShortcutTarget target = targetResolution.target();
            if (target.isError()) {
                ctx.sendReply(PendingMessage.ofString(target.errorMessage()));
                return;
            }

            lastTarget.put(ctx.senderUserId(), target);

            if (ctx.args().length > targetResolution.consumedArgs() + 1) {
                ctx.sendReply(PendingMessage.ofString(CommandUsage.M));
                return;
            }

            String mod = ctx.args().length == targetResolution.consumedArgs() + 1
                    ? ctx.args()[targetResolution.consumedArgs()]
                    : null;

            taskCoordinator.runImageRequest(
                    ctx,
                    "Beatmap",
                    () -> APIHelper.getBeatmapResponse(target, mod, accessTokenProvider.apply(ctx.senderUserId())),
                    replyFactory::beatmapMessage
            );
        } else {
            if (lastTarget.get(ctx.senderUserId()) != null) {
                ShortcutTarget target = lastTarget.get(ctx.senderUserId());
                taskCoordinator.runImageRequest(
                        ctx,
                        "Beatmap",
                        () -> APIHelper.getBeatmapResponse(target, null, accessTokenProvider.apply(ctx.senderUserId())),
                        replyFactory::beatmapMessage
                );
                return;
            }

            ctx.sendReply(PendingMessage.ofString(CommandUsage.M));
        }
    }

    public void handleAp(Context ctx) {
        ShortcutTarget target;

        if (ctx.args().length >= 1) {
            TargetResolution targetResolution = resolver.resolveTargetWithOptionalMention(ctx.args(), ctx.senderUserId());
            target = targetResolution.target();
            if (target.isError()) {
                ctx.sendReply(PendingMessage.ofString(target.errorMessage()));
                return;
            }

            lastTarget.put(ctx.senderUserId(), target);
        } else {
            if (lastTarget.get(ctx.senderUserId()) != null) {
                target = lastTarget.get(ctx.senderUserId());
            } else {
                ctx.sendReply(PendingMessage.ofString(CommandUsage.AP));
                return;
            }
        }

        taskCoordinator.runApiRequest(ctx, "Audio Preview", () -> {
                    final long id = APIHelper.lookupBeatmapset(target, accessTokenProvider.apply(ctx.senderUserId()));
                    ctx.sendReply(PendingMessage.ofVoiceUrl("https://b.ppy.sh/preview/" + id + ".mp3").doUpload(false));
                }
        );
    }

    public void handleBpv(Context ctx) {
        if (ctx.args().length < 1) {
            ctx.sendReply(PendingMessage.ofString(CommandUsage.BPV));
            return;
        }

        TargetResolution targetResolution = resolver.resolveTargetWithOptionalMention(
                ctx.args(), ctx.senderUserId());
        if (ctx.args().length > targetResolution.consumedArgs() + 1) {
            ctx.sendReply(PendingMessage.ofString(CommandUsage.BPV));
            return;
        }

        ShortcutTarget target = targetResolution.target();
        if (target.isError()) {
            ctx.sendReply(PendingMessage.ofString(target.errorMessage()));
            return;
        }

        String mods = ctx.args().length == targetResolution.consumedArgs() + 1
                ? ctx.args()[targetResolution.consumedArgs()]
                : null;
        lastTarget.put(ctx.senderUserId(), target);

        taskCoordinator.runReplayRequest(
                ctx,
                "Beatmap Preview Render",
                qqUpload -> {
                    APIHelper.ReplayTaskInfo task = APIHelper.createBeatmapPreviewTask(
                            target, mods, accessTokenProvider.apply(ctx.senderUserId()), qqUpload);
                    videoRenderRecord.updateRenderTask(ctx.senderUserId(), task.taskId());
                    return task;
                },
                replyFactory::replayMessage
        );
    }

    public void handleBgp(Context ctx) {
        ShortcutTarget target;

        if (ctx.args().length >= 1) {
            TargetResolution targetResolution = resolver.resolveTargetWithOptionalMention(ctx.args(), ctx.senderUserId());
            target = targetResolution.target();
            if (target.isError()) {
                ctx.sendReply(PendingMessage.ofString(target.errorMessage()));
                return;
            }

            lastTarget.put(ctx.senderUserId(), target);
        } else {
            if (lastTarget.get(ctx.senderUserId()) != null) {
                target = lastTarget.get(ctx.senderUserId());
            } else {
                ctx.sendReply(PendingMessage.ofString(CommandUsage.BGP));
                return;
            }
        }

        taskCoordinator.runImageRequest(
                ctx,
                "Background Preview",
                () -> APIHelper.getBeatmapBgResponse(target, accessTokenProvider.apply(ctx.senderUserId())),
                replyFactory::bgpMessage
        );
    }

    public void handleDl(Context ctx) {
        ShortcutTarget target;

        if (ctx.args().length == 0) {
            target = lastTarget.get(ctx.senderUserId());
        } else if (ctx.args().length <= 2) {
            TargetResolution targetResolution = resolver.resolveTargetWithOptionalMention(ctx.args(), ctx.senderUserId());
            if (ctx.args().length != targetResolution.consumedArgs()) {
                ctx.sendReply(PendingMessage.ofString(CommandUsage.DL));
                return;
            }

            target = targetResolution.target();
            if (target.isError()) {
                ctx.sendReply(PendingMessage.ofString(target.errorMessage()));
                return;
            }

            lastTarget.put(ctx.senderUserId(), target);
        } else {
            target = null;
        }

        if (target == null) {
            ctx.sendReply(PendingMessage.ofString(CommandUsage.DL));
            return;
        }

        taskCoordinator.runApiRequest(ctx, "Download Beatmap", () ->
                ctx.sendReply(replyFactory.dlMessage(
                        ctx,
                        APIHelper.getLookupBeatmapsetResponse(target, accessTokenProvider.apply(ctx.senderUserId()))
                ))
        );
    }

    public void handleMs(Context ctx) {
        ShortcutTarget target;
        if (ctx.args().length == 0) {
            target = lastTarget.get(ctx.senderUserId());
        } else if (ctx.args().length <= 2) {
            TargetResolution targetResolution = resolver.resolveTargetWithOptionalMention(ctx.args(), ctx.senderUserId());
            if (ctx.args().length != targetResolution.consumedArgs()) {
                ctx.sendReply(PendingMessage.ofString("用法：/ms <谱面集ID 或 快捷查询>"));
                return;
            }
            target = targetResolution.target();
            if (target.isError()) {
                ctx.sendReply(PendingMessage.ofString(target.errorMessage()));
                return;
            }

            lastTarget.put(ctx.senderUserId(), target);
        } else {
            target = null;
        }

        if (target == null) {
            ctx.sendReply(PendingMessage.ofString("用法：/ms <谱面集ID 或 快捷查询>"));
            return;
        }

        taskCoordinator.runImageRequest(
                ctx,
                "Beatmapset",
                () -> APIHelper.getBeatmapsetResponse(target, accessTokenProvider.apply(ctx.senderUserId())),
                replyFactory::beatmapsetMessage
        );
    }

    public void handleSms(Context ctx) {
        final SearchQuery searchQuery = resolver.resolveSearchQuery(ctx.query());
        if (searchQuery == null) {
            ctx.sendReply(PendingMessage.ofString("用法：/sms [#页数] <搜索关键字>"));
            return;
        }
        taskCoordinator.runApiRequest(ctx, "Search Beatmapset", () -> {
                    Response<List<SearchResultItem>> searchResponse = APIHelper.searchBeatmapSetResponse(searchQuery);
                    ctx.sendReply(replyFactory.searchMessage(ctx, searchResponse, searchQuery));
                });
    }

}
