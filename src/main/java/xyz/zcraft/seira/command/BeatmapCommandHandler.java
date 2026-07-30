package xyz.zcraft.seira.command;

import xyz.zcraft.seira.api.APIHelper;
import xyz.zcraft.seira.api.data.Response;
import xyz.zcraft.seira.api.data.SearchQuery;
import xyz.zcraft.seira.api.data.SearchResultItem;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.resolution.ShortcutTarget;
import xyz.zcraft.seira.command.resolution.TargetResolution;

import java.util.List;
import java.util.function.Function;

final class BeatmapCommandHandler {
    private final Resolver resolver;
    private final TargetHistory lastTarget;
    private final TaskCoordinator taskCoordinator;
    private final ReplyFactory replyFactory;
    private final Function<String, String> accessTokenProvider;

    BeatmapCommandHandler(
            Resolver resolver,
            TargetHistory targetHistory,
            TaskCoordinator taskCoordinator,
            ReplyFactory replyFactory,
            Function<String, String> accessTokenProvider
    ) {
        this.resolver = resolver;
        this.lastTarget = targetHistory;
        this.taskCoordinator = taskCoordinator;
        this.replyFactory = replyFactory;
        this.accessTokenProvider = accessTokenProvider;
    }

    RouteDecision handleDaily(Context ctx) {
        return taskCoordinator.queueApiRequest(ctx, "Daily Challenge", () -> PendingMessage.ofMarkdownRaw(APIHelper.getDaily()));
    }

    RouteDecision handleM(Context ctx) {
        if (ctx.args().length >= 1) {
            TargetResolution targetResolution = resolver.resolveTargetWithOptionalMention(ctx.args(), ctx.senderUserId());
            ShortcutTarget target = targetResolution.target();
            if (target.isError()) {
                return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
            }

            lastTarget.put(ctx.senderUserId(), target);

            if (ctx.args().length > targetResolution.consumedArgs() + 1) {
                return RouteDecision.sync(PendingMessage.ofString(CommandUsage.M));
            }

            String mod = ctx.args().length == targetResolution.consumedArgs() + 1
                    ? ctx.args()[targetResolution.consumedArgs()]
                    : null;

            return taskCoordinator.queueImageRequest(
                    ctx,
                    "Beatmap",
                    () -> APIHelper.getBeatmapResponse(target, mod, accessTokenProvider.apply(ctx.senderUserId())),
                    replyFactory::beatmapMessage
            );
        } else {
            if (lastTarget.get(ctx.senderUserId()) != null) {
                ShortcutTarget target = lastTarget.get(ctx.senderUserId());
                return taskCoordinator.queueImageRequest(
                        ctx,
                        "Beatmap",
                        () -> APIHelper.getBeatmapResponse(target, null, accessTokenProvider.apply(ctx.senderUserId())),
                        replyFactory::beatmapMessage
                );
            }

            return RouteDecision.sync(PendingMessage.ofString(CommandUsage.M));
        }
    }

    RouteDecision handleAp(Context ctx) {
        ShortcutTarget target;

        if (ctx.args().length >= 1) {
            TargetResolution targetResolution = resolver.resolveTargetWithOptionalMention(ctx.args(), ctx.senderUserId());
            target = targetResolution.target();
            if (target.isError()) {
                return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
            }

            lastTarget.put(ctx.senderUserId(), target);
        } else {
            if (lastTarget.get(ctx.senderUserId()) != null) {
                target = lastTarget.get(ctx.senderUserId());
            } else return RouteDecision.sync(PendingMessage.ofString(CommandUsage.AP));
        }

        return taskCoordinator.queueApiRequest(
                ctx,
                "Audio Preview",
                () -> {
                    final long id = APIHelper.lookupBeatmapset(target, accessTokenProvider.apply(ctx.senderUserId()));
                    return PendingMessage.ofVoiceUrl("https://b.ppy.sh/preview/" + id + ".mp3").doUpload(false);
                }
        );
    }

    RouteDecision handleBgp(Context ctx) {
        ShortcutTarget target;

        if (ctx.args().length >= 1) {
            TargetResolution targetResolution = resolver.resolveTargetWithOptionalMention(ctx.args(), ctx.senderUserId());
            target = targetResolution.target();
            if (target.isError()) {
                return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
            }

            lastTarget.put(ctx.senderUserId(), target);
        } else {
            if (lastTarget.get(ctx.senderUserId()) != null) {
                target = lastTarget.get(ctx.senderUserId());
            } else return RouteDecision.sync(PendingMessage.ofString(CommandUsage.BGP));
        }

        return taskCoordinator.queueImageRequest(
                ctx,
                "Background Preview",
                () -> APIHelper.getBeatmapBgResponse(target, accessTokenProvider.apply(ctx.senderUserId())),
                replyFactory::bgpMessage
        );
    }

    RouteDecision handleDl(Context ctx) {
        ShortcutTarget target;

        if (ctx.args().length == 0) {
            target = lastTarget.get(ctx.senderUserId());
        } else if (ctx.args().length <= 2) {
            TargetResolution targetResolution = resolver.resolveTargetWithOptionalMention(ctx.args(), ctx.senderUserId());
            if (ctx.args().length != targetResolution.consumedArgs()) {
                return RouteDecision.sync(PendingMessage.ofString(CommandUsage.DL));
            }

            target = targetResolution.target();
            if (target.isError()) {
                return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
            }

            lastTarget.put(ctx.senderUserId(), target);
        } else {
            target = null;
        }

        if (target == null) {
            return RouteDecision.sync(PendingMessage.ofString(CommandUsage.DL));
        }

        return taskCoordinator.queueApiRequest(
                ctx,
                "Download Beatmap",
                () -> replyFactory.dlMessage(
                        ctx,
                        APIHelper.getLookupBeatmapsetResponse(target, accessTokenProvider.apply(ctx.senderUserId()))
                )
        );
    }

    RouteDecision handleMs(Context ctx) {
        ShortcutTarget target;
        if (ctx.args().length == 0) {
            target = lastTarget.get(ctx.senderUserId());
        } else if (ctx.args().length <= 2) {
            TargetResolution targetResolution = resolver.resolveTargetWithOptionalMention(ctx.args(), ctx.senderUserId());
            if (ctx.args().length != targetResolution.consumedArgs()) {
                return RouteDecision.sync(PendingMessage.ofString("用法：/ms <谱面集ID 或 快捷查询>"));
            }
            target = targetResolution.target();
            if (target.isError()) {
                return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
            }

            lastTarget.put(ctx.senderUserId(), target);
        } else {
            target = null;
        }

        if (target == null) {
            return RouteDecision.sync(PendingMessage.ofString("用法：/ms <谱面集ID 或 快捷查询>"));
        }

        return taskCoordinator.queueImageRequest(
                ctx,
                "Beatmapset",
                () -> APIHelper.getBeatmapsetResponse(target, accessTokenProvider.apply(ctx.senderUserId())),
                replyFactory::beatmapsetMessage
        );
    }

    RouteDecision handleSms(Context ctx) {
        final SearchQuery searchQuery = resolver.resolveSearchQuery(ctx.query());
        if (searchQuery == null) {
            return RouteDecision.sync(PendingMessage.ofString("用法：/sms [#页数] <搜索关键字>"));
        }
        return taskCoordinator.queueApiRequest(
                ctx,
                "Search Beatmapset",
                () -> {
                    Response<List<SearchResultItem>> searchResponse = APIHelper.searchBeatmapSetResponse(searchQuery);
                    return replyFactory.searchMessage(ctx, searchResponse, searchQuery);
                });
    }

}
