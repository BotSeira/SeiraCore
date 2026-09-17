package xyz.zcraft.seira.command.handler;

import xyz.zcraft.osu.model.User;
import xyz.zcraft.osu.model.UserExtended;
import xyz.zcraft.seira.api.APIHelper;
import xyz.zcraft.seira.api.data.FriendEntry;
import xyz.zcraft.seira.api.data.OsuToken;
import xyz.zcraft.seira.api.data.Response;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.command.TaskCoordinator;
import xyz.zcraft.seira.command.parse.Resolver;
import xyz.zcraft.seira.command.parse.ShortcutTarget;
import xyz.zcraft.seira.command.parse.TargetResolution;
import xyz.zcraft.seira.command.parse.UserRefResolution;
import xyz.zcraft.seira.command.reply.CommandUsage;
import xyz.zcraft.seira.command.reply.ReplyFactory;
import xyz.zcraft.seira.data.UserRef;
import xyz.zcraft.seira.db.UserDataStore;
import xyz.zcraft.seira.util.OsuAuthHelper;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;

import static xyz.zcraft.seira.command.reply.ReplyFactory.at;

public final class SocialCommandHandler {
    private final Resolver resolver;
    private final OsuAuthHelper authHelper;
    private final TaskCoordinator taskCoordinator;
    private final ReplyFactory replyFactory;
    private final Function<String, String> accessTokenProvider;
    private final Function<String, String> avatarProvider;

    public SocialCommandHandler(
            Resolver resolver,
            OsuAuthHelper authHelper,
            TaskCoordinator taskCoordinator,
            ReplyFactory replyFactory,
            Function<String, String> accessTokenProvider,
            Function<String, String> avatarProvider
    ) {
        this.resolver = resolver;
        this.authHelper = authHelper;
        this.taskCoordinator = taskCoordinator;
        this.replyFactory = replyFactory;
        this.accessTokenProvider = accessTokenProvider;
        this.avatarProvider = avatarProvider;
    }

    public void handleMp(Context ctx) {
        if (resolver.resolveBoundUid(ctx.senderUserId()) == null) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + CommandUsage.NO_BIND));
            return;
        }

        OsuToken token = authHelper.updateTokenAndGet(ctx.senderUserId());

        if (token == null) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(CommandUsage.REBIND));
            return;
        }

        try (var _ = taskCoordinator.beginRequest(ctx, "Multiplayer Room")) {
            var response = APIHelper.getMultiplayerRoom(token.accessToken());
            ctx.sendReply(replyFactory.mpMessage(ctx, response));
        }
    }

    public void handleF(Context ctx, boolean all) {
        final Long uid = resolver.resolveBoundUid(ctx.senderUserId());
        if (uid == null) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + CommandUsage.NO_BIND));
            return;
        }

        if (ctx.argumentCount() == 0) {
            handleFriendList(ctx, all);
        } else if (ctx.command().equals("f")
                && ctx.inGroup()
                && ctx.argumentCount() == 1
                && resolver.looksLikeMention(ctx.argument(0))) {
            handleFriendStatus(ctx);
        } else {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + CommandUsage.F));
        }
    }

    public void handleFriendStatus(Context ctx) {
        final Long selfId = resolver.resolveBoundUid(ctx.senderUserId());

        if (ctx.argumentCount() == 0) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "用法：/mu @someone\n> 注: 读取@需要开启权限。"));
        }

        final UserRefResolution targetRef = resolver.resolveUserRefArgument(ctx.argument(0));

        if (targetRef.errorMessage() != null) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + targetRef.errorMessage()));
            return;
        }

        final UserExtended targetUser = APIHelper.getUserRaw(targetRef.userRef());
        final String targetOsuAvatar = targetUser.getAvatarUrl();

        boolean selfFollowed;
        final AtomicReference<Boolean> targetFollowed = new AtomicReference<>();

        final OsuToken selfToken = authHelper.updateTokenAndGet(ctx.senderUserId());
        final var selfUser = APIHelper.getSelf(selfToken.accessToken()).getContent();
        final String selfOsuAvatar = selfUser.getAvatarUrl();

        if (targetUser.getId() == selfUser.getId()) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + " 和 `" + targetUser.getUsername() + "` 是一个人喵。"));
            return;
        }

        final List<FriendEntry> selfFollowedList = APIHelper.getFollowed(selfToken.accessToken()).getContent();
        updateFriends(selfId, selfFollowedList);
        final Set<User> users = new HashSet<>(selfFollowedList.stream().map(FriendEntry::user).toList());

        users.add(selfUser);
        users.add(targetUser);

        selfFollowed = selfFollowedList.stream()
                .anyMatch(e -> e.user().getId() == targetUser.getId());

        selfFollowedList.stream()
                .filter(e -> e.user().getId() == targetUser.getId())
                .findFirst()
                .ifPresent(e -> targetFollowed.set(e.mutual()));

        final var targetOpenId = UserDataStore.findGroupOpenIdByUid(ctx.groupId(), targetUser.getId()).orElse(null);

        if (targetFollowed.get() == null && targetOpenId != null) {
            final List<FriendEntry> targetFollowedList;
            final OsuToken target = authHelper.updateTokenAndGet(targetOpenId);
            if (target != null) {
                targetFollowedList = APIHelper.getFollowed(target.accessToken()).getContent();
                targetFollowed.set(targetFollowedList.stream().anyMatch(e -> e.user().getId() == selfId));
                users.addAll(targetFollowedList.stream().map(FriendEntry::user).toList());
            }
        }

        UserDataStore.storeUserInfo(users);

        ctx.sendReply(replyFactory.friendStatusMessage(
                        ctx.senderUserId(), selfId, selfOsuAvatar,
                        UserDataStore.findUsername(selfId).orElse("未知"),
                        avatarProvider.apply(ctx.senderUserId()),

                        targetOpenId, targetUser.getId(), targetOsuAvatar,
                        targetUser.getUsername(),
                        avatarProvider.apply(targetOpenId),

                        selfFollowed, targetFollowed.get()
                )
        );
    }

    public void handleFriendList(Context ctx, boolean all) {
        final Long uid = resolver.resolveBoundUid(ctx.senderUserId());
        OsuToken token = authHelper.updateTokenAndGet(ctx.senderUserId());

        if (token == null) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(CommandUsage.REBIND));
            return;
        }

        try (var _ = taskCoordinator.beginRequest(ctx, "Friend List")) {
            final Response<UserExtended> self = APIHelper.getSelf(token.accessToken());
            final Response<List<FriendEntry>> response = APIHelper.getFollowed(token.accessToken());
            final List<FriendEntry> friendEntries = response.getContent();

            final Predicate<Long> filter;
            if (ctx.inGroup() && !all) {
                final var groupIds = UserDataStore.findBoundUidsByGroup(ctx.groupId());
                filter = groupIds::contains;
            } else {
                filter = (_) -> true;
            }

            UserDataStore.storeUserInfo(self.getContent().getId(), self.getContent().getUsername());
            UserDataStore.storeUserInfo(response.getContent().stream()
                    .map(FriendEntry::user)
                    .toList());

            updateFriends(uid, friendEntries);

            final List<Long> follower = UserDataStore.findFollower(uid);

            final List<User> mutual = new LinkedList<>();
            final List<User> onlyFollowed = new LinkedList<>();
            final List<User> onlyFollower = new LinkedList<>();

            for (FriendEntry e : friendEntries) {
                if (!filter.test(e.user().getId())) continue;
                if (follower.contains(e.user().getId())) {
                    mutual.add(e.user());
                } else {
                    onlyFollowed.add(e.user());
                }
            }

            for (Long i : follower) {
                if (!filter.test(i)) continue;
                if (friendEntries.stream().noneMatch(entry -> Objects.equals(entry.user().getId(), i))) {
                    User u = new User();
                    u.setId(i);
                    u.setUsername(UserDataStore.findUsername(i).orElse("未知-" + i));
                    onlyFollower.add(u);
                }
            }

            long allMutualCount = friendEntries.stream().filter(FriendEntry::mutual).count();

            final Comparator<User> userComparator = Comparator.comparing(User::isOnline, Comparator.reverseOrder()).thenComparing(User::getUsername);
            mutual.sort(userComparator);
            onlyFollower.sort(userComparator);
            onlyFollowed.sort(userComparator);

            ctx.sendReply(replyFactory.friendMessage(
                    ctx, all, self.getContent(), friendEntries.size(), allMutualCount,
                    mutual, onlyFollowed, onlyFollower
            ));
        }
    }

    private void updateFriends(Long uid, List<FriendEntry> newFriends) {
        final List<Long> ids = newFriends.stream().map(e -> e.user().getId()).toList();
        final List<Long> origFollower = UserDataStore.findFollower(uid);

        origFollower.stream()
                .filter(i -> !ids.contains(i))
                .forEach(i -> UserDataStore.removeFollowed(uid, i));

        for (FriendEntry friendEntry : newFriends) {
            if (!UserDataStore.haveFollowed(uid, friendEntry.user().getId())) {
                UserDataStore.storeFollowed(uid, friendEntry.user().getId());
            }

            if (friendEntry.mutual()) {
                if (!UserDataStore.haveFollowed(friendEntry.user().getId(), uid)) {
                    UserDataStore.storeFollowed(friendEntry.user().getId(), uid);
                }
            }
        }
    }

    public void handleFclear(Context ctx) {
        Long uid = resolver.resolveBoundUid(ctx.senderUserId());
        if (uid == null) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + CommandUsage.NO_BIND));
            return;
        }

        ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) +
                "已清除 " + UserDataStore.clearFollowed(uid) + " 条好友记录。"
        ));
    }

    public void handleLb(Context ctx) {
        if (ctx.args().length == 0) {
            if (ctx.groupId() != null && !ctx.groupId().isBlank()) {
                List<Long> groupBoundUids = UserDataStore.findBoundUidsByGroup(ctx.groupId());
                if (groupBoundUids.isEmpty()) {
                    ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "本群还没有已绑定的玩家，请先使用 /bind"));
                    return;
                }

                try (var _ = taskCoordinator.beginRequest(ctx, "Leaderboard")) {
                    var response = APIHelper.getLeaderboardResponse(groupBoundUids);
                    ctx.sendReply(taskCoordinator.imageMessage(response, replyFactory.lbMessage(ctx, response)));
                }
                return;
            }
            Long uid = resolver.resolveBoundUid(ctx.senderUserId());
            if (uid == null) {
                ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + CommandUsage.NO_BIND));
                return;
            }

            try (var _ = taskCoordinator.beginRequest(ctx, "Leaderboard")) {
                var response = APIHelper.getLeaderboardResponse(List.of(uid));
                ctx.sendReply(taskCoordinator.imageMessage(response, replyFactory.lbMessage(ctx, response)));
            }
        } else if (ctx.args().length == 1 || ctx.args().length == 2) {
            TargetResolution targetResolution = resolver.resolveTargetWithOptionalMention(ctx.args(), ctx.senderUserId());
            ShortcutTarget target = targetResolution.target();
            if (target.isError()) {
                ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + target.errorMessage()));
                return;
            }

            int remainingArgs = ctx.args().length - targetResolution.consumedArgs();
            if (remainingArgs == 0) {
                if (ctx.groupId() != null && !ctx.groupId().isBlank()) {
                    List<Long> groupBoundUids = UserDataStore.findBoundUidsByGroup(ctx.groupId());
                    if (groupBoundUids.isEmpty()) {
                        ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "本群还没有已绑定的玩家，请先使用 /bind"));
                        return;
                    }
                    try (var _ = taskCoordinator.beginRequest(ctx, "Map Leaderboard")) {
                        long beatmapId = APIHelper.lookupBeatmap(target, accessTokenProvider.apply(ctx.senderUserId()));
                        var response = APIHelper.getGroupLeaderboardResponse(beatmapId, groupBoundUids);
                        ctx.sendReply(taskCoordinator.imageMessage(response, replyFactory.lbMessage(ctx, response)));
                    }
                    return;
                }
                Long uid = resolver.resolveBoundUid(ctx.senderUserId());
                if (uid == null) {
                    ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + CommandUsage.NO_BIND));
                    return;
                }

                try (var _ = taskCoordinator.beginRequest(ctx, "Map Leaderboard")) {
                    long beatmapId = APIHelper.lookupBeatmap(target, accessTokenProvider.apply(ctx.senderUserId()));
                    var response = APIHelper.getGroupLeaderboardResponse(beatmapId, List.of(uid));
                    ctx.sendReply(taskCoordinator.imageMessage(response, replyFactory.lbMessage(ctx, response)));
                }
                return;
            }

            if (remainingArgs != 1) {
                ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "用法：/lb <谱面ID或快捷查询> [玩家ID列表(逗号分隔)]"));
                return;
            }

            String[] uidTokens = ctx.args()[targetResolution.consumedArgs()].split(",");
            if (uidTokens.length == 0) {
                ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "玩家ID列表不能为空。用法：/lb <谱面ID或快捷查询> [玩家ID列表(逗号分隔)]"));
                return;
            }

            List<Long> uids = new LinkedList<>();
            for (String uidToken : uidTokens) {
                Long uid = resolver.parsePositiveLong(uidToken.trim());
                if (uid == null) {
                    ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "玩家ID列表包含非法值。用法：/lb <谱面ID或快捷查询> [玩家ID列表(逗号分隔)]"));
                    return;
                }
                uids.add(uid);
            }

            try (var _ = taskCoordinator.beginRequest(ctx, "Map Leaderboard")) {
                long beatmapId = APIHelper.lookupBeatmap(target, accessTokenProvider.apply(ctx.senderUserId()));
                var response = APIHelper.getGroupLeaderboardResponse(beatmapId, uids);
                ctx.sendReply(taskCoordinator.imageMessage(response, replyFactory.lbMessage(ctx, response)));
            }
        } else {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "用法：/lb <谱面ID或快捷查询> [玩家ID列表(逗号分隔)]"));
        }
    }

    public void handleSup(Context ctx) {
        final UserRef target;

        if (ctx.argumentCount() == 0) {
            Long targetId = resolver.resolveBoundUid(ctx.senderUserId());
            if (targetId == null) {
                ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + CommandUsage.NO_BIND));
                return;
            }
            target = new UserRef.ByUid(targetId);
        } else if (ctx.argumentCount() == 1) {
            final UserRefResolution res = resolver.resolveUserRefArgument(ctx.argument(0));
            if (res.errorMessage() != null) {
                ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + res.errorMessage()));
                return;
            }
            target = res.userRef();
        } else {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + CommandUsage.SUP));
            return;
        }

        final UserExtended user = APIHelper.getUserRaw(target);
        final String openId = UserDataStore.findGroupOpenIdByUid(ctx.groupId(), user.getId()).orElse(null);

        ctx.sendReply(replyFactory.supMessage(ctx, user.getUsername(), openId, user.isSupporter(), user.getHasSupported(), user.getSupportLevel()));
    }
}
