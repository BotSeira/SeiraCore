package xyz.zcraft.seira.command.handler;

import xyz.zcraft.osu.model.User;
import xyz.zcraft.osu.model.UserExtended;
import xyz.zcraft.seira.api.APIHelper;
import xyz.zcraft.seira.api.data.FriendEntry;
import xyz.zcraft.seira.api.data.OsuToken;
import xyz.zcraft.seira.api.data.Response;
import xyz.zcraft.seira.binding.UserDataStore;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.*;
import xyz.zcraft.seira.command.parse.Resolver;
import xyz.zcraft.seira.command.reply.CommandUsage;
import xyz.zcraft.seira.command.reply.ReplyFactory;
import xyz.zcraft.seira.command.parse.ShortcutTarget;
import xyz.zcraft.seira.command.parse.TargetResolution;
import xyz.zcraft.seira.command.route.RouteDecision;
import xyz.zcraft.seira.util.OsuAuthHelper;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

public final class SocialCommandHandler {
    private final Resolver resolver;
    private final OsuAuthHelper authHelper;
    private final TaskCoordinator taskCoordinator;
    private final ReplyFactory replyFactory;
    private final Function<String, String> accessTokenProvider;

    public SocialCommandHandler(
            Resolver resolver,
            OsuAuthHelper authHelper,
            TaskCoordinator taskCoordinator,
            ReplyFactory replyFactory,
            Function<String, String> accessTokenProvider
    ) {
        this.resolver = resolver;
        this.authHelper = authHelper;
        this.taskCoordinator = taskCoordinator;
        this.replyFactory = replyFactory;
        this.accessTokenProvider = accessTokenProvider;
    }

    public RouteDecision handleMp(Context ctx) {
        if (resolver.resolveBoundUid(ctx.senderUserId()) == null) {
            return RouteDecision.sync(PendingMessage.ofString(CommandUsage.NO_BIND));
        }

        OsuToken token = authHelper.updateTokenAndGet(ctx.senderUserId());

        if (token == null) {
            return RouteDecision.sync(PendingMessage.ofMarkdownRaw(CommandUsage.REBIND));
        }

        return taskCoordinator.queueApiRequest(ctx, "Multiplayer Room",
                () -> replyFactory.mpMessage(ctx, APIHelper.getMultiplayerRoom(token.accessToken())));
    }

    public RouteDecision handleF(Context ctx, boolean all) {
        final Long uid = resolver.resolveBoundUid(ctx.senderUserId());
        if (uid == null) {
            return RouteDecision.sync(PendingMessage.ofString(CommandUsage.NO_BIND));
        }

        OsuToken token = authHelper.updateTokenAndGet(ctx.senderUserId());

        if (token == null) {
            return RouteDecision.sync(PendingMessage.ofMarkdownRaw(CommandUsage.REBIND));
        }

        return taskCoordinator.queueApiRequest(ctx, "Friend List", () -> {
            final Response<UserExtended> self = APIHelper.getSelf(token.accessToken());
            final Response<List<FriendEntry>> response = APIHelper.getFollowed(token.accessToken());
            final List<FriendEntry> content = response.getContent();
            final List<Long> ids = content.stream().map(e -> e.user().getId()).toList();

            final Predicate<Long> filter;
            if (ctx.inGroup() && !all) {
                final var groupIds = UserDataStore.findBoundUidsByGroup(ctx.groupId());
                filter = groupIds::contains;
            } else {
                filter = (_) -> true;
            }

            UserDataStore.storeUserInfo(self.getContent().getId(), self.getContent().getUsername());
            response.getContent().stream()
                    .map(FriendEntry::user)
                    .forEach(u -> UserDataStore.storeUserInfo(u.getId(), u.getUsername()));

            final List<Long> origFollower = UserDataStore.findFollower(uid);

            origFollower.stream()
                    .filter(i -> !ids.contains(i))
                    .forEach(i -> UserDataStore.removeFollowed(uid, i));

            for (FriendEntry friendEntry : content) {
                if (!UserDataStore.haveFollowed(uid, friendEntry.user().getId())) {
                    UserDataStore.storeFollowed(uid, friendEntry.user().getId());
                }

                if (friendEntry.mutual()) {
                    if (!UserDataStore.haveFollowed(friendEntry.user().getId(), uid)) {
                        UserDataStore.storeFollowed(friendEntry.user().getId(), uid);
                    }
                }
            }

            final List<Long> follower = UserDataStore.findFollower(uid);

            final List<User> mutual = new LinkedList<>();
            final List<User> onlyFollowed = new LinkedList<>();
            final List<User> onlyFollower = new LinkedList<>();

            for (FriendEntry e : content) {
                if (!filter.test(e.user().getId())) continue;
                if (follower.contains(e.user().getId())) {
                    mutual.add(e.user());
                } else {
                    onlyFollowed.add(e.user());
                }
            }

            for (Long i : follower) {
                if (!filter.test(i)) continue;
                if (content.stream().noneMatch(entry -> Objects.equals(entry.user().getId(), i))) {
                    User u = new User();
                    u.setId(i);
                    u.setUsername(UserDataStore.findUsername(i).orElse("未知-" + i));
                    onlyFollower.add(u);
                }
            }

            long allMutualCount = content.stream().filter(FriendEntry::mutual).count();

            final Comparator<User> userComparator = Comparator.comparing(User::isOnline, Comparator.reverseOrder()).thenComparing(User::getUsername);
            mutual.sort(userComparator);
            onlyFollower.sort(userComparator);
            onlyFollowed.sort(userComparator);

            return replyFactory.friendMessage(ctx, all, self.getContent(), content.size(), allMutualCount, mutual, onlyFollowed, onlyFollower);
        });
    }

    public RouteDecision handleFclear(Context ctx) {
        Long uid = resolver.resolveBoundUid(ctx.senderUserId());
        if (uid == null) {
            return RouteDecision.sync(PendingMessage.ofString(CommandUsage.NO_BIND));
        }

        return RouteDecision.sync(PendingMessage.ofString(
                "已清除 " + UserDataStore.clearFollowed(uid) + " 条好友记录。"
        ));
    }

    public RouteDecision handleLb(Context ctx) {
        if (ctx.args().length == 0) {
            if (ctx.groupId() != null && !ctx.groupId().isBlank()) {
                List<Long> groupBoundUids = UserDataStore.findBoundUidsByGroup(ctx.groupId());
                if (groupBoundUids.isEmpty()) {
                    return RouteDecision.sync(PendingMessage.ofString("本群还没有已绑定的玩家，请先使用 /bind"));
                }

                return taskCoordinator.queueImageRequest(
                        ctx,
                        "Leaderboard",
                        () -> APIHelper.getLeaderboardResponse(groupBoundUids),
                        replyFactory::lbMessage
                );
            }
            Long uid = resolver.resolveBoundUid(ctx.senderUserId());
            if (uid == null) {
                return RouteDecision.sync(PendingMessage.ofString(CommandUsage.NO_BIND));
            }

            return taskCoordinator.queueImageRequest(
                    ctx,
                    "Leaderboard",
                    () -> APIHelper.getLeaderboardResponse(List.of(uid)),
                    replyFactory::lbMessage
            );
        } else if (ctx.args().length == 1 || ctx.args().length == 2) {
            TargetResolution targetResolution = resolver.resolveTargetWithOptionalMention(ctx.args(), ctx.senderUserId());
            ShortcutTarget target = targetResolution.target();
            if (target.isError()) {
                return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
            }

            int remainingArgs = ctx.args().length - targetResolution.consumedArgs();
            if (remainingArgs == 0) {
                if (ctx.groupId() != null && !ctx.groupId().isBlank()) {
                    List<Long> groupBoundUids = UserDataStore.findBoundUidsByGroup(ctx.groupId());
                    if (groupBoundUids.isEmpty()) {
                        return RouteDecision.sync(PendingMessage.ofString("本群还没有已绑定的玩家，请先使用 /bind"));
                    }
                    return taskCoordinator.queueImageRequest(
                            ctx,
                            "Map Leaderboard",
                            () -> APIHelper.getGroupLeaderboardResponse(target, groupBoundUids, accessTokenProvider.apply(ctx.senderUserId())),
                            replyFactory::lbMessage
                    );
                }
                Long uid = resolver.resolveBoundUid(ctx.senderUserId());
                if (uid == null) {
                    return RouteDecision.sync(PendingMessage.ofString(CommandUsage.NO_BIND));
                }

                return taskCoordinator.queueImageRequest(
                        ctx,
                        "Map Leaderboard",
                        () -> APIHelper.getGroupLeaderboardResponse(target, List.of(uid), accessTokenProvider.apply(ctx.senderUserId())),
                        replyFactory::lbMessage
                );
            }

            if (remainingArgs != 1) {
                return RouteDecision.sync(PendingMessage.ofString("用法：/lb <谱面ID或快捷查询> [玩家ID列表(逗号分隔)]"));
            }

            String[] uidTokens = ctx.args()[targetResolution.consumedArgs()].split(",");
            if (uidTokens.length == 0) {
                return RouteDecision.sync(PendingMessage.ofString("玩家ID列表不能为空。用法：/lb <谱面ID或快捷查询> [玩家ID列表(逗号分隔)]"));
            }

            List<Long> uids = new LinkedList<>();
            for (String uidToken : uidTokens) {
                Long uid = resolver.parsePositiveLong(uidToken.trim());
                if (uid == null) {
                    return RouteDecision.sync(PendingMessage.ofString("玩家ID列表包含非法值。用法：/lb <谱面ID或快捷查询> [玩家ID列表(逗号分隔)]"));
                }
                uids.add(uid);
            }

            return taskCoordinator.queueImageRequest(
                    ctx,
                    "Map Leaderboard",
                    () -> APIHelper.getGroupLeaderboardResponse(target, uids, accessTokenProvider.apply(ctx.senderUserId())),
                    replyFactory::lbMessage
            );
        } else {
            return RouteDecision.sync(PendingMessage.ofString("用法：/lb <谱面ID或快捷查询> [玩家ID列表(逗号分隔)]"));
        }
    }

}
