package xyz.zcraft.seira.command;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.seira.api.APIHelper;
import xyz.zcraft.seira.api.Response;
import xyz.zcraft.seira.binding.BindingHelper;
import xyz.zcraft.seira.binding.UserDataStore;
import xyz.zcraft.seira.bot.MessageSender;
import xyz.zcraft.seira.command.resolution.ShortcutTarget;
import xyz.zcraft.seira.command.resolution.TargetResolution;
import xyz.zcraft.seira.command.resolution.UidListResolution;
import xyz.zcraft.seira.command.resolution.UidResolution;
import xyz.zcraft.seira.config.AppConfig;
import xyz.zcraft.seira.data.*;
import xyz.zcraft.osu.model.User;
import xyz.zcraft.seira.util.OsuAuthHelper;
import xyz.zcraft.seira.util.ThreadHelper;
import xyz.zcraft.seira.util.TimeDurationParser;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Router {
    private static final Logger LOG = LogManager.getLogger(Router.class);

    private static final String PREFIX = "/";
    private final MessageSender messageSender;
    private final VideoRenderRecord videoRenderRecord = new VideoRenderRecord();
    private final AppConfig config;
    private final Resolver argumentResolver;
    private final ReplyFactory replyFactory;
    private final TaskCoordinator taskCoordinator;
    private final OsuAuthHelper authHelper;

    public Router(MessageSender messageSender, AppConfig config) {
        this.messageSender = messageSender;
        this.config = config;
        this.argumentResolver = new Resolver();
        this.replyFactory = new ReplyFactory(config);
        this.taskCoordinator = new TaskCoordinator(messageSender);
        this.authHelper = new OsuAuthHelper(config.binding());
    }

    public void onPrivateMessageReceived(String userId, String messageId, String rawContent) {
        handleMessageReceived(userId, null, userId, messageId, rawContent, false);
    }

    public void onGroupMessageReceived(String groupId, String senderUserId, String messageId, String rawContent) {
        handleMessageReceived(groupId, groupId, senderUserId, messageId, rawContent, true);
    }

    private void handleMessageReceived(String targetId, String groupId, String userId, String messageId, String rawContent, boolean groupMessage) {
        LOG.info("Received {} message {} from {}: {}", groupMessage ? "group" : "private", messageId, userId, rawContent);
        AtomicInteger messageSeqCounter = new AtomicInteger(1);
        try {
            if (groupMessage && groupId != null && !groupId.isBlank() && userId != null && !userId.isBlank()) {
                UserDataStore.upsertGroupMember(groupId, userId);
            }

            RouteDecision routeDecision = route(rawContent, userId, groupId, messageId);
            if (routeDecision == null) {
                return;
            }

            taskCoordinator.sendOutboundMessage(targetId, messageId, groupMessage, routeDecision.initialMessage(), messageSeqCounter);

            ApiTask apiTask = routeDecision.apiTask();
            if (apiTask != null) {
                ThreadHelper.run(() -> taskCoordinator.processApiTask(targetId, messageId, groupMessage, apiTask, messageSeqCounter));
            }
        } catch (Exception e) {
            taskCoordinator.sendOutboundMessage(targetId, messageId, groupMessage, PendingMessage.ofString("处理指令时发生错误，请稍后再试。"), messageSeqCounter);
            LOG.error("Failed to process inbound message {}", messageId, e);
        }
    }

    private static final Pattern RS_QUERY = Pattern.compile("^rs(\\d+)$");

    private String preProcess(String rawContent) {
        Matcher matcher = RS_QUERY.matcher(rawContent);
        if (matcher.matches()) {
            return "/s rs" + matcher.group(1);
        }

        return rawContent;
    }

    protected RouteDecision route(String rawContent, String senderUserId, String groupId, String messageId) {
        if (rawContent == null || !rawContent.trim().startsWith(PREFIX)) {
            return null;
        }

        String body = rawContent.trim().substring(PREFIX.length()).trim();
        if (body.isEmpty()) {
            return RouteDecision.sync(PendingMessage.ofString("请输入指令。使用/help获取帮助。"));
        }

        body = preProcess(body);

        String[] parts = body.split("\\s+");
        String command = parts[0].toLowerCase();
        String query = body.substring(command.length()).trim();
        String[] args = Arrays.copyOfRange(parts, 1, parts.length);

        switch (command) {
            case "bind" -> {
                if (senderUserId == null || senderUserId.isBlank()) {
                    return RouteDecision.sync(PendingMessage.ofString("无法识别你的用户ID，暂时无法绑定。请稍后重试。"));
                }

                if (UserDataStore.findBoundUid(senderUserId) != null) {
                    return RouteDecision.sync(PendingMessage.ofString("你已经绑定了玩家ID，如果要更换绑定请先使用 /unbind 解绑当前玩家ID。"));
                }

                if (config.binding().requireLogin()) {
                    if (args.length != 0) {
                        return RouteDecision.sync(PendingMessage.ofString("用法：/bind"));
                    }
                    final var bindingTask = BindingHelper.createBindingTask(senderUserId, messageId, (user, token) -> {
                        UserDataStore.bind(senderUserId, user.getId());
                        UserDataStore.storeToken(senderUserId, token);
                    });
                    return RouteDecision.sync(replyFactory.bindMessage(config.binding(), bindingTask, groupId == null || groupId.isBlank()));
                } else {
                    if (args.length != 1) {
                        return RouteDecision.sync(PendingMessage.ofString("用法：/bind <玩家ID>"));
                    }
                    Integer uid = argumentResolver.parsePositiveInt(args[0]);
                    if (uid == null) {
                        return RouteDecision.sync(PendingMessage.ofString("玩家ID必须是正整数。用法：/bind <玩家ID>"));
                    }

                    UserDataStore.bind(senderUserId, uid);
                    return RouteDecision.sync(PendingMessage.ofString("绑定成功，已绑定到玩家ID: " + uid));
                }
            }
            case "unbind" -> {
                if (senderUserId == null || senderUserId.isBlank()) {
                    return RouteDecision.sync(PendingMessage.ofString("无法识别你的用户ID，暂时无法解绑。请稍后重试。"));
                }
                if (args.length != 0) {
                    return RouteDecision.sync(PendingMessage.ofString("用法：/unbind"));
                }
                boolean removed = UserDataStore.unbind(senderUserId);
                return RouteDecision.sync(PendingMessage.ofString(removed
                        ? "解绑成功。"
                        : "你当前还没有绑定玩家ID，无需解绑。"));
            }
            case "clearhistory" -> {
                if (senderUserId == null || senderUserId.isBlank()) {
                    return RouteDecision.sync(PendingMessage.ofString("无法识别你的用户ID，无法清除历史记录。请稍后重试。"));
                }
                if (args.length != 0) {
                    return RouteDecision.sync(PendingMessage.ofString("用法：/clearhistory"));
                }
                int removed = UserDataStore.clearGroupMember(senderUserId);
                return RouteDecision.sync(PendingMessage.ofString("清除了 " + removed + " 条群聊记录。"));
            }
            case "bo" -> {
                if (args.length == 2) {
                    Integer n = argumentResolver.parsePositiveInt(args[0]);
                    if (n == null) {
                        return RouteDecision.sync(PendingMessage.ofString(Usages.BO_USAGE));
                    }
                    UidResolution uidResolution = argumentResolver.resolveUidArgument(args[1]);
                    if (uidResolution.errorMessage() != null) {
                        return RouteDecision.sync(PendingMessage.ofString(uidResolution.errorMessage()));
                    }
                    if (uidResolution.uid() == null) {
                        return RouteDecision.sync(PendingMessage.ofString(Usages.BO_USAGE));
                    }
                    var uid = uidResolution.uid();

                    return taskCoordinator.queueImageRequest(
                            "bo",
                            () -> APIHelper.getBoNResponse(n, uid),
                            replyFactory::boMessage
                    );
                } else if (args.length == 1) {
                    Integer n = argumentResolver.parsePositiveInt(args[0]);
                    if (n == null) {
                        return RouteDecision.sync(PendingMessage.ofString(Usages.BO_USAGE));
                    }
                    Long uid = argumentResolver.resolveBoundUid(senderUserId);
                    if (uid == null) {
                        return RouteDecision.sync(PendingMessage.ofString(Usages.NO_BIND_TIP));
                    }

                    return taskCoordinator.queueImageRequest(
                            "bo",
                            () -> APIHelper.getBoNResponse(n, uid),
                            replyFactory::boMessage
                    );
                } else if (args.length == 0) {
                    ShortcutTarget target = argumentResolver.parseTarget("bo1", senderUserId);
                    if (target.isError()) {
                        return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
                    }

                    return taskCoordinator.queueImageRequest(
                            "bo",
                            () -> APIHelper.getScoreResponse(target),
                            replyFactory::boMessage
                    );
                } else {
                    return RouteDecision.sync(PendingMessage.ofString(Usages.BO_USAGE));
                }
            }
            case "daily" -> {
                return taskCoordinator.queueApiRequest("daily", () -> PendingMessage.ofMarkdownRaw(APIHelper.getDaily()));
            }
            case "mp" -> {
                if (!config.binding().requireLogin()) {
                    return RouteDecision.sync(PendingMessage.ofString("本指令未启用：需要进行用户登录鉴权。"));
                }

                OsuToken token = authHelper.getTokenFor(senderUserId);

                if (token == null) {
                    return RouteDecision.sync(PendingMessage.ofString("无法获取用户凭据。"));
                }

                return taskCoordinator.queueApiRequest("mp", () -> replyFactory.mpMessage(APIHelper.getMultiplayerRoom(token.accessToken())));
            }
            case "rs" -> {
                if (args.length == 2) {
                    Integer n = argumentResolver.parsePositiveInt(args[0]);
                    if (n == null) {
                        return RouteDecision.sync(PendingMessage.ofString(Usages.RS_USAGE));
                    }

                    UidResolution uidResolution = argumentResolver.resolveUidArgument(args[1]);
                    if (uidResolution.errorMessage() != null) {
                        return RouteDecision.sync(PendingMessage.ofString(uidResolution.errorMessage()));
                    }

                    if (uidResolution.uid() == null) {
                        return RouteDecision.sync(PendingMessage.ofString(Usages.RS_USAGE));
                    }

                    return taskCoordinator.queueImageRequest(
                            "rs",
                            () -> APIHelper.getRecentResponse(n, uidResolution.uid()),
                            replyFactory::rsMessage
                    );
                } else if (args.length == 1) {
                    Integer n = argumentResolver.parsePositiveInt(args[0]);
                    if (n == null) {
                        return RouteDecision.sync(PendingMessage.ofString(Usages.RS_USAGE));
                    }
                    Long uid = argumentResolver.resolveBoundUid(senderUserId);
                    if (uid == null) {
                        return RouteDecision.sync(PendingMessage.ofString(Usages.NO_BIND_TIP));
                    }

                    return taskCoordinator.queueImageRequest(
                            "rs",
                            () -> APIHelper.getRecentResponse(n, uid),
                            replyFactory::rsMessage
                    );
                } else if (args.length == 0) {
                    ShortcutTarget target = argumentResolver.parseTarget("rs1", senderUserId);
                    if (target.isError()) {
                        return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
                    }

                    return taskCoordinator.queueImageRequest(
                            "rs",
                            () -> APIHelper.getScoreResponse(target),
                            replyFactory::scoreMessage
                    );
                } else {
                    return RouteDecision.sync(PendingMessage.ofString(Usages.RS_USAGE));
                }
            }
            case "m" -> {
                if (args.length >= 1) {
                    TargetResolution targetResolution = argumentResolver.resolveTargetWithOptionalMention(args, senderUserId);
                    ShortcutTarget target = targetResolution.target();
                    if (target.isError()) {
                        return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
                    }

                    if (args.length > targetResolution.consumedArgs() + 1) {
                        return RouteDecision.sync(PendingMessage.ofString(Usages.M_USAGE));
                    }

                    String mod = args.length == targetResolution.consumedArgs() + 1
                            ? args[targetResolution.consumedArgs()]
                            : null;

                    return taskCoordinator.queueImageRequest(
                            "m",
                            () -> APIHelper.getBeatmapResponse(target, mod, authHelper.getTokenFor(senderUserId).accessToken()),
                            replyFactory::beatmapMessage
                    );
                } else {
                    return RouteDecision.sync(PendingMessage.ofString(Usages.M_USAGE));
                }
            }
            case "f" -> {
                if (!config.binding().requireLogin()) {
                    return RouteDecision.sync(PendingMessage.ofString("本指令未启用：需要进行用户登录鉴权。"));
                }

                Long uid = argumentResolver.resolveBoundUid(senderUserId);
                if (uid == null) {
                    return RouteDecision.sync(PendingMessage.ofString(Usages.NO_BIND_TIP));
                }

                OsuToken token = authHelper.getTokenFor(senderUserId);

                if (token == null) {
                    return RouteDecision.sync(PendingMessage.ofString("无法获取用户凭据。"));
                }

                return taskCoordinator.queueApiRequest("f", () -> {
                    final Response<User> self = APIHelper.getSelf(token.accessToken());
                    final Response<List<FriendEntry>> response = APIHelper.getFollowed(token.accessToken());
                    final List<FriendEntry> content = response.getContent();
                    final List<Long> ids = content.stream().map(e -> e.user().getId()).toList();

                    final Predicate<Long> filter = (
                            (groupId == null || groupId.isBlank())
                                    ? (_) -> true
                                    : (i) -> UserDataStore.findBoundUidsByGroup(groupId).contains(i)
                    );

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

                    return replyFactory.friendMessage(!(groupId == null || groupId.isBlank()), content.size(), mutual, onlyFollowed, onlyFollower);
                });
            }
            case "dl" -> {
                if (args.length < 1 || args.length > 2) {
                    return RouteDecision.sync(PendingMessage.ofString(Usages.DL_USAGE));
                }

                TargetResolution targetResolution = argumentResolver.resolveTargetWithOptionalMention(args, senderUserId);
                if (args.length != targetResolution.consumedArgs()) {
                    return RouteDecision.sync(PendingMessage.ofString(Usages.DL_USAGE));
                }

                ShortcutTarget target = targetResolution.target();
                if (target.isError()) {
                    return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
                }

                return taskCoordinator.queueApiRequest(
                        "dl",
                        () -> replyFactory.dlMessage(APIHelper.lookupBeatmapset(
                                target, authHelper.getTokenFor(senderUserId).accessToken())
                        )
                );
            }
            case "s" -> {
                if (args.length < 1 || args.length > 2) {
                    return RouteDecision.sync(PendingMessage.ofString(Usages.S_USAGE));
                }

                TargetResolution targetResolution = argumentResolver.resolveTargetWithOptionalMention(args, senderUserId);
                if (args.length != targetResolution.consumedArgs()) {
                    return RouteDecision.sync(PendingMessage.ofString(Usages.S_USAGE));
                }
                ShortcutTarget target = targetResolution.target();
                if (target.isError()) {
                    return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
                }

                return taskCoordinator.queueImageRequest(
                        "s",
                        () -> APIHelper.getScoreResponse(target),
                        replyFactory::scoreMessage
                );
            }
            case "r" -> {
                if (args.length < 1 || args.length > 3) {
                    return RouteDecision.sync(PendingMessage.ofString(Usages.R_USAGE));
                }

                TargetResolution targetResolution = argumentResolver.resolveTargetWithOptionalMention(args, senderUserId);
                if (args.length - targetResolution.consumedArgs() > 1) {
                    return RouteDecision.sync(PendingMessage.ofString(Usages.R_USAGE));
                }

                ShortcutTarget target = targetResolution.target();
                if (target.isError()) {
                    return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
                }

                TimeDurationParser.TimeRange range = null;

                if (args.length == 2) {
                    try {
                        range = TimeDurationParser.parseRange(args[1]);
                    } catch (IllegalArgumentException e) {
                        return RouteDecision.sync(PendingMessage.ofString("无法解析时间范围"));
                    }
                }

                TimeDurationParser.TimeRange finalRange = range;
                return taskCoordinator.queueReplayTask(
                        "r",
                        () -> {
                            APIHelper.ReplayTaskInfo replayRenderTask = APIHelper.createReplayRenderTask(target, finalRange);
                            videoRenderRecord.updateRenderTask(senderUserId, replayRenderTask.taskId());
                            return replayRenderTask;
                        },
                        replyFactory::replayMessage);
            }
            case "rsc" -> {
                if (args.length < 1 || args.length > 3) {
                    return RouteDecision.sync(PendingMessage.ofString(Usages.RSC_USAGE));
                }

                if (groupId == null || groupId.isBlank()) {
                    return RouteDecision.sync(PendingMessage.ofString("/rsc 仅支持群聊使用。"));
                }

                TargetResolution targetResolution = argumentResolver.resolveTargetWithOptionalMention(args, senderUserId);
                if (args.length - targetResolution.consumedArgs() > 2) {
                    return RouteDecision.sync(PendingMessage.ofString(Usages.RSC_USAGE));
                }

                ShortcutTarget target = targetResolution.target();
                if (target.isError()) {
                    return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
                }

                String extraUidArg = null;
                TimeDurationParser.TimeRange range = null;

                for (int i = args.length - targetResolution.consumedArgs() - 1; i < args.length; i++) {
                    if (args[i].startsWith("+")) {
                        extraUidArg = args[i];
                    } else if (TimeDurationParser.isTimeRange(args[i])) {
                        range = TimeDurationParser.parseRange(args[i]);
                    } else {
                        return RouteDecision.sync(PendingMessage.ofString(Usages.RSC_USAGE));
                    }
                }

                UidListResolution uidListResolution = argumentResolver.resolveRscUidList(groupId, extraUidArg);
                if (uidListResolution.errorMessage() != null) {
                    return RouteDecision.sync(PendingMessage.ofString(uidListResolution.errorMessage()));
                }
                String[] uidArray = uidListResolution.uids();

                TimeDurationParser.TimeRange finalRange = range;

                if (target.isMacro()) {
                    return taskCoordinator.queueReplayTask(
                            "rsc",
                            () -> {
                                var task = APIHelper.createReplayShowcaseTask(target, uidArray, finalRange);
                                videoRenderRecord.updateRenderTask(senderUserId, task.taskId());
                                return task;
                            },
                            replyFactory::replayMessage);
                }

                if (target.explicitId() == null) {
                    return RouteDecision.sync(PendingMessage.ofString(Usages.RSC_USAGE));
                }

                return taskCoordinator.queueReplayTask(
                        "rsc",
                        () -> {
                            var task = APIHelper.createShowcaseRenderTaskByBeatmap(target.explicitId(), uidArray, finalRange);
                            videoRenderRecord.updateRenderTask(senderUserId, task.taskId());
                            return task;
                        },
                        replyFactory::replayMessage);
            }
            case "ms" -> {
                if (args.length < 1 || args.length > 2) {
                    return RouteDecision.sync(PendingMessage.ofString("用法：/ms <铺面集ID 或 快捷查询>"));
                }

                TargetResolution targetResolution = argumentResolver.resolveTargetWithOptionalMention(args, senderUserId);
                if (args.length != targetResolution.consumedArgs()) {
                    return RouteDecision.sync(PendingMessage.ofString("用法：/ms <铺面集ID 或 快捷查询>"));
                }
                ShortcutTarget target = targetResolution.target();
                if (target.isError()) {
                    return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
                }

                return taskCoordinator.queueImageRequest(
                        "ms",
                        () -> APIHelper.getBeatmapsetResponse(target, authHelper.getTokenFor(senderUserId).accessToken()),
                        replyFactory::beatmapsetMessage
                );
            }
            case "sms" -> {
                final SearchQuery searchQuery = argumentResolver.resolveSearchQuery(query);
                if (searchQuery == null) {
                    return RouteDecision.sync(PendingMessage.ofString("用法：/sms [#页数] <搜索关键字>"));
                }
                return taskCoordinator.queueApiRequest(
                        "sms",
                        () -> {
                            Response<List<SearchResultItem>> searchResponse = APIHelper.searchBeatmapSetResponse(searchQuery);
                            return replyFactory.searchMessage(searchResponse, searchQuery);
                        });
            }
            case "lb" -> {
                if (args.length == 0) {
                    if (groupId != null && !groupId.isBlank()) {
                        List<Long> groupBoundUids = UserDataStore.findBoundUidsByGroup(groupId);
                        if (groupBoundUids.isEmpty()) {
                            return RouteDecision.sync(PendingMessage.ofString("本群还没有已绑定的玩家，请先使用 /bind <玩家ID>"));
                        }
                        String[] uidArray = groupBoundUids.stream().map(String::valueOf).toArray(String[]::new);

                        return taskCoordinator.queueImageRequest(
                                "lb",
                                () -> APIHelper.getLeaderboardResponse(uidArray),
                                replyFactory::lbMessage
                        );
                    }
                    Long uid = argumentResolver.resolveBoundUid(senderUserId);
                    if (uid == null) {
                        return RouteDecision.sync(PendingMessage.ofString(Usages.NO_BIND_TIP));
                    }

                    return taskCoordinator.queueImageRequest(
                            "lb",
                            () -> APIHelper.getLeaderboardResponse(new String[]{String.valueOf(uid)}),
                            replyFactory::lbMessage
                    );
                } else if (args.length == 1 || args.length == 2) {
                    TargetResolution targetResolution = argumentResolver.resolveTargetWithOptionalMention(args, senderUserId);
                    ShortcutTarget target = targetResolution.target();
                    if (target.isError()) {
                        return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
                    }

                    int remainingArgs = args.length - targetResolution.consumedArgs();
                    if (remainingArgs == 0) {
                        if (groupId != null && !groupId.isBlank()) {
                            List<Long> groupBoundUids = UserDataStore.findBoundUidsByGroup(groupId);
                            if (groupBoundUids.isEmpty()) {
                                return RouteDecision.sync(PendingMessage.ofString("本群还没有已绑定的玩家，请先使用 /bind <玩家ID>"));
                            }
                            String[] uidArray = groupBoundUids.stream().map(String::valueOf).toArray(String[]::new);
                            return taskCoordinator.queueImageRequest(
                                    "lbm",
                                    () -> APIHelper.getGroupLeaderboardResponse(target, uidArray),
                                    replyFactory::lbMessage
                            );
                        }
                        Long uid = argumentResolver.resolveBoundUid(senderUserId);
                        if (uid == null) {
                            return RouteDecision.sync(PendingMessage.ofString(Usages.NO_BIND_TIP));
                        }

                        return taskCoordinator.queueImageRequest(
                                "lbm",
                                () -> APIHelper.getGroupLeaderboardResponse(target, new String[]{String.valueOf(uid)}),
                                replyFactory::lbMessage
                        );
                    }

                    if (remainingArgs != 1) {
                        return RouteDecision.sync(PendingMessage.ofString("用法：/lb <铺面ID或快捷查询> [玩家ID列表(逗号分隔)]"));
                    }

                    String[] uidTokens = args[targetResolution.consumedArgs()].split(",");
                    if (uidTokens.length == 0) {
                        return RouteDecision.sync(PendingMessage.ofString("玩家ID列表不能为空。用法：/lb <铺面ID或快捷查询> [玩家ID列表(逗号分隔)]"));
                    }
                    String[] uidArray = new String[uidTokens.length];
                    for (int i = 0; i < uidTokens.length; i++) {
                        Integer uid = argumentResolver.parsePositiveInt(uidTokens[i].trim());
                        if (uid == null) {
                            return RouteDecision.sync(PendingMessage.ofString("玩家ID列表包含非法值。用法：/lb <铺面ID或快捷查询> [玩家ID列表(逗号分隔)]"));
                        }
                        uidArray[i] = String.valueOf(uid);
                    }

                    return taskCoordinator.queueImageRequest(
                            "lbm",
                            () -> APIHelper.getGroupLeaderboardResponse(target, uidArray),
                            replyFactory::lbMessage
                    );
                } else {
                    return RouteDecision.sync(PendingMessage.ofString("用法：/lb <铺面ID或快捷查询> [玩家ID列表(逗号分隔)]"));
                }
            }
            case "status" -> {
                return RouteDecision.sync(PendingMessage.ofString(APIHelper.getServerStatus()));
            }
            case "u" -> {
                // TODO: 暂时使用bo8
                if (args.length == 1) {
                    return route("/bo 8 " + args[0], senderUserId, groupId, messageId);
                } else {
                    return RouteDecision.sync(PendingMessage.ofString("用法：/u <玩家ID>"));
                }
            }
            case "rstat" -> {
                if (args.length == 1) {
                    return RouteDecision.sync(replyFactory.replayStatMessage(args[0], APIHelper.getRenderStat(args[0])));
                } else if (args.length == 0) {
                    if (videoRenderRecord.hasRenderTask(senderUserId)) {
                        final String jobId = videoRenderRecord.getRenderTask(senderUserId);
                        return RouteDecision.sync(replyFactory.replayStatMessage(jobId, APIHelper.getRenderStat(jobId)));
                    }
                    return RouteDecision.sync(PendingMessage.ofString("未找到渲染请求"));
                } else {
                    return RouteDecision.sync(PendingMessage.ofString("用法：/rstat [任务ID]"));
                }
            }
            case "inspect" -> {
                return RouteDecision.sync(replyFactory.inspectMessage(senderUserId, isAdmin(senderUserId), groupId, messageId));
            }
            case "help" -> {
                return RouteDecision.sync(PendingMessage.ofString("""
                        可用指令：
                        /bind <玩家ID> - 绑定你的玩家ID
                        /unbind - 解除你的玩家ID绑定
                        /clearhistory - 清除你在群聊中的记录
                        /f - 获取好友列表
                        /bo <个数> [玩家ID] - 获取BoN图谱
                        /rs <个数> [玩家ID] - 获取最近成绩图谱
                        /m <铺面ID> - 获取铺面图谱
                        /ms <铺面集ID> - 获取铺面集图谱
                        /r <成绩ID或快捷查询> - 生成成绩回放视频
                        /rsc <铺面ID或快捷查询> [+用户ID列表] - 生成同屏回放视频
                        /rstat [任务ID] - 查询渲染进度
                        /dl <ID或快捷查询> - 获取镜像下载链接
                        /sms [#页数] <关键字> - 搜索铺面集
                        /lb <铺面ID> [玩家ID列表] - 获取指定铺面排行榜
                        /daily - 获取每日挑战
                        /mp - 获取多人房间列表
                        /status - 获取服务器状态
                        /help - 显示此帮助信息"""));
            }
            case "debug.upload" -> {
                if (!config.seira().debugMode()) {
                    return RouteDecision.sync(PendingMessage.ofString("未知指令。使用/help获取帮助。"));
                }

                if (!isAdmin(senderUserId)) {
                    return RouteDecision.sync(PendingMessage.ofString("你没有权限使用此指令。"));
                }

                if (args.length != 3) {
                    return RouteDecision.sync(PendingMessage.ofString("用法：/debug.upload <type> <cos> <url>"));
                }

                String typeStr = args[0];
                String cosStr = args[1];
                String urlStr = args[2];

                FileInfo fileInfo;
                if (groupId != null && !groupId.isBlank()) {
                    fileInfo = messageSender.uploadGroupMedia(groupId, Integer.parseInt(typeStr), urlStr, "true".equals(cosStr));
                } else {
                    fileInfo = messageSender.uploadPrivateMedia(senderUserId, Integer.parseInt(typeStr), urlStr, "true".equals(cosStr));
                }

                return RouteDecision.sync(PendingMessage.ofString(fileInfo != null
                        ? "上传成功，fileId: " + fileInfo
                        : "上传失败，请检查日志获取详情"));
            }
            case "debug.test" -> {
                if (!config.seira().debugMode()) {
                    return RouteDecision.sync(PendingMessage.ofString("未知指令。使用/help获取帮助。"));
                }

                if (!isAdmin(senderUserId)) {
                    return RouteDecision.sync(PendingMessage.ofString("你没有权限使用此指令。"));
                }

                return RouteDecision.sync(replyFactory.testMessage());
            }
            default -> {
                return RouteDecision.sync(PendingMessage.ofString("未知指令。使用/help获取帮助。"));
            }
        }
    }

    private boolean isAdmin(String openId) {
        final List<String> adminIds = config.seira().adminIds();
        if (adminIds == null || adminIds.isEmpty()) {
            return false;
        }
        return adminIds.contains(openId);
    }

    private static final class Usages {
        public static final String BO_USAGE = "用法：/bo <个数> [玩家ID/@用户]";
        public static final String NO_BIND_TIP = "你还没有绑定玩家ID，请先使用 /bind <玩家ID>";
        public static final String RS_USAGE = "用法：/rs <个数> [玩家ID/@用户]";
        public static final String M_USAGE = "用法：/m <铺面ID 或 快捷查询> [Mod]";
        public static final String DL_USAGE = "用法：/dl <铺面集ID 或 快捷查询>";
        public static final String S_USAGE = "用法：/s <成绩ID 或 快捷查询>";
        private static final String R_USAGE = "用法：/r <成绩ID 或 快捷查询>";
        private static final String RSC_USAGE = "用法：/rsc <铺面ID或快捷查询> [+用户ID列表，逗号分隔]";
    }
}
