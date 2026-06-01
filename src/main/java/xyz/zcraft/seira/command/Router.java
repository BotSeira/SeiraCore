package xyz.zcraft.seira.command;

import lombok.Getter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.util.encoders.Base64Encoder;
import xyz.zcraft.osu.model.User;
import xyz.zcraft.osu.model.UserExtended;
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
import xyz.zcraft.seira.util.OsuAuthHelper;
import xyz.zcraft.seira.util.ThreadHelper;
import xyz.zcraft.seira.util.TimeDurationParser;

import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

public class Router {
    private static final Logger LOG = LogManager.getLogger(Router.class);

    private static final String PREFIX = "/";
    private final MessageSender messageSender;
    private final VideoRenderRecord videoRenderRecord = new VideoRenderRecord();
    @Getter
    private final HashMap<String, APIHelper.ReplayRenderResult> renderResults = new HashMap<>();
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
        this.taskCoordinator = new TaskCoordinator(this, messageSender);
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
            final boolean group = groupMessage && groupId != null && !groupId.isBlank();
            if (group && userId != null && !userId.isBlank()) {
                UserDataStore.upsertGroupMember(groupId, userId);
            }

            RouteDecision routeDecision = route(rawContent, userId, groupId, messageId);
            if (routeDecision == null) {
                return;
            }

            if (routeDecision.initialMessage() != null) {
                if (!routeDecision.enqueueMessage() || !group || config.seira().queueMessageInGroup()) {
                    boolean res = taskCoordinator.sendOutboundMessage(
                            targetId, messageId, groupMessage,
                            routeDecision.initialMessage(), messageSeqCounter
                    );

                    if (routeDecision.onSent() != null) {
                        routeDecision.onSent().accept(res);
                    }
                }
            }

            ApiTask apiTask = routeDecision.apiTask();
            if (apiTask != null) {
                ThreadHelper.run(() -> taskCoordinator.processApiTask(targetId, messageId, groupMessage, apiTask, messageSeqCounter));
            }
        } catch (Exception e) {
            taskCoordinator.sendOutboundMessage(targetId, messageId, groupMessage, PendingMessage.ofString("处理指令时发生错误，请稍后再试。"), messageSeqCounter);
            LOG.error("Failed to process inbound message {}", messageId, e);
        }
    }

    protected RouteDecision route(String rawContent, String senderUserId, String groupId, String messageId) {
        if (rawContent == null || !rawContent.trim().startsWith(PREFIX)) {
            return null;
        }

        String body = rawContent.trim().substring(PREFIX.length()).trim();
        if (body.isEmpty()) {
            return RouteDecision.sync(PendingMessage.ofString("请输入指令。使用/help获取帮助。"));
        }

        body = argumentResolver.preProcess(body);

        String[] parts = body.split("\\s+");
        String command = parts[0].toLowerCase();
        String query = body.substring(command.length()).trim();
        String[] args = Arrays.copyOfRange(parts, 1, parts.length);

        final Context ctx = new Context(senderUserId, groupId);
        final CommandContext commandContext = new CommandContext(ctx, senderUserId, groupId, messageId, args, query);

        return switch (command) {
            case "bind" -> handleBind(commandContext);
            case "unbind" -> handleUnbind(commandContext);
            case "clearhistory" -> handleClearHistory(commandContext);
            case "bo" -> handleBo(commandContext);
            case "daily" -> handleDaily(commandContext);
            case "mp" -> handleMp(commandContext);
            case "rs" -> handleRs(commandContext);
            case "m" -> handleM(commandContext);
            case "f" -> handleF(commandContext, false);
            case "fall" -> handleF(commandContext, true);
            case "fclear" -> handleFclear(commandContext);
            case "dl" -> handleDl(commandContext);
            case "s" -> handleS(commandContext);
            case "sa" -> handleSa(commandContext);
            case "ma" -> handleMa(commandContext);
            case "r" -> handleR(commandContext);
            case "rsc" -> handleRsc(commandContext);
            case "ms" -> handleMs(commandContext);
            case "sms" -> handleSms(commandContext);
            case "lb" -> handleLb(commandContext);
            case "status" -> handleStatus();
            case "u" -> handleU(commandContext);
            case "rstat" -> handleRstat(commandContext);
            case "inspect" -> handleInspect(commandContext);
            case "help" -> handleHelp();
            case "debug.upload" -> handleDebugUpload(commandContext);
            case "debug.test" -> handleDebugTest(commandContext);
            case "debug.message" -> handleDebugMessage(commandContext);
            default -> handleUnknown();
        };
    }

    private RouteDecision handleBind(CommandContext commandContext) {
        if (commandContext.senderUserId == null || commandContext.senderUserId.isBlank()) {
            return RouteDecision.sync(PendingMessage.ofString("无法识别你的用户ID，暂时无法绑定。请稍后重试。"));
        }

        if (UserDataStore.findBoundUid(commandContext.senderUserId) != null) {
            return RouteDecision.sync(PendingMessage.ofString("你已经绑定了玩家ID，如果要更换绑定请先使用 /unbind 解绑当前玩家ID。"));
        }

        if (config.binding().requireLogin()) {
            if (commandContext.args.length != 0) {
                return RouteDecision.sync(PendingMessage.ofString("用法：/bind"));
            }
            final var bindingTask = BindingHelper.createBindingTask(commandContext.senderUserId, commandContext.messageId, (user, token) -> {
                UserDataStore.bind(commandContext.senderUserId, user.getId());
                UserDataStore.storeToken(commandContext.senderUserId, token);
            });
            return RouteDecision.sync(replyFactory.bindMessage(commandContext.ctx, config.binding(), bindingTask,
                    commandContext.groupId == null || commandContext.groupId.isBlank()));
        } else {
            if (commandContext.args.length != 1) {
                return RouteDecision.sync(PendingMessage.ofString("用法：/bind <玩家ID>"));
            }
            Integer uid = argumentResolver.parsePositiveInt(commandContext.args[0]);
            if (uid == null) {
                return RouteDecision.sync(PendingMessage.ofString("玩家ID必须是正整数。用法：/bind <玩家ID>"));
            }

            UserDataStore.bind(commandContext.senderUserId, uid);
            return RouteDecision.sync(PendingMessage.ofString("绑定成功，已绑定到玩家ID: " + uid));
        }
    }

    private RouteDecision handleUnbind(CommandContext commandContext) {
        if (commandContext.senderUserId == null || commandContext.senderUserId.isBlank()) {
            return RouteDecision.sync(PendingMessage.ofString("无法识别你的用户ID，暂时无法解绑。请稍后重试。"));
        }
        if (commandContext.args.length != 0) {
            return RouteDecision.sync(PendingMessage.ofString("用法：/unbind"));
        }
        boolean removed = UserDataStore.unbind(commandContext.senderUserId);
        return RouteDecision.sync(PendingMessage.ofString(removed
                ? "解绑成功。"
                : "你当前还没有绑定玩家ID，无需解绑。"));
    }

    private RouteDecision handleClearHistory(CommandContext commandContext) {
        if (commandContext.senderUserId == null || commandContext.senderUserId.isBlank()) {
            return RouteDecision.sync(PendingMessage.ofString("无法识别你的用户ID，无法清除历史记录。请稍后重试。"));
        }
        if (commandContext.args.length != 0) {
            return RouteDecision.sync(PendingMessage.ofString("用法：/clearhistory"));
        }
        int removed = UserDataStore.clearGroupMember(commandContext.senderUserId);
        return RouteDecision.sync(PendingMessage.ofString("清除了 " + removed + " 条群聊记录。"));
    }

    private RouteDecision handleBo(CommandContext commandContext) {
        if (commandContext.args.length == 2) {
            Integer n = argumentResolver.parsePositiveInt(commandContext.args[0]);
            if (n == null) {
                return RouteDecision.sync(PendingMessage.ofString(Usages.BO_USAGE));
            }
            UidResolution uidResolution = argumentResolver.resolveUidArgument(commandContext.args[1]);
            if (uidResolution.errorMessage() != null) {
                return RouteDecision.sync(PendingMessage.ofString(uidResolution.errorMessage()));
            }
            if (uidResolution.uid() == null) {
                return RouteDecision.sync(PendingMessage.ofString(Usages.BO_USAGE));
            }
            var uid = uidResolution.uid();

            return taskCoordinator.queueImageRequest(
                    commandContext.ctx,
                    "Best Scores",
                    () -> APIHelper.getBoNResponse(n, uid),
                    replyFactory::boMessage
            );
        } else if (commandContext.args.length == 1) {
            Integer n = argumentResolver.parsePositiveInt(commandContext.args[0]);
            if (n == null) {
                return RouteDecision.sync(PendingMessage.ofString(Usages.BO_USAGE));
            }
            Long uid = argumentResolver.resolveBoundUid(commandContext.senderUserId);
            if (uid == null) {
                return RouteDecision.sync(PendingMessage.ofString(Usages.NO_BIND_TIP));
            }

            return taskCoordinator.queueImageRequest(
                    commandContext.ctx,
                    "Best Scores",
                    () -> APIHelper.getBoNResponse(n, uid),
                    replyFactory::boMessage
            );
        } else if (commandContext.args.length == 0) {
            ShortcutTarget target = argumentResolver.parseTarget("bo1", commandContext.senderUserId);
            if (target.isError()) {
                return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
            }

            return taskCoordinator.queueImageRequest(
                    commandContext.ctx,
                    "Score",
                    () -> APIHelper.getScoreResponse(target),
                    replyFactory::scoreMessage
            );
        } else {
            return RouteDecision.sync(PendingMessage.ofString(Usages.BO_USAGE));
        }
    }

    private RouteDecision handleDaily(CommandContext commandContext) {
        return taskCoordinator.queueApiRequest(commandContext.ctx, "Daily Challenge", () -> PendingMessage.ofMarkdownRaw(APIHelper.getDaily()));
    }

    private RouteDecision handleMp(CommandContext commandContext) {
        if (!config.binding().requireLogin()) {
            return RouteDecision.sync(PendingMessage.ofString("本指令未启用：需要进行用户登录鉴权。"));
        }

        OsuToken token = authHelper.getTokenFor(commandContext.senderUserId);

        if (token == null) {
            return RouteDecision.sync(PendingMessage.ofString("无法获取用户凭据。"));
        }

        return taskCoordinator.queueApiRequest(commandContext.ctx, "Multiplayer Room",
                () -> replyFactory.mpMessage(commandContext.ctx, APIHelper.getMultiplayerRoom(token.accessToken())));
    }

    private RouteDecision handleRs(CommandContext commandContext) {
        if (commandContext.args.length == 2) {
            Integer n = argumentResolver.parsePositiveInt(commandContext.args[0]);
            if (n == null) {
                return RouteDecision.sync(PendingMessage.ofString(Usages.RS_USAGE));
            }

            UidResolution uidResolution = argumentResolver.resolveUidArgument(commandContext.args[1]);
            if (uidResolution.errorMessage() != null) {
                return RouteDecision.sync(PendingMessage.ofString(uidResolution.errorMessage()));
            }

            if (uidResolution.uid() == null) {
                return RouteDecision.sync(PendingMessage.ofString(Usages.RS_USAGE));
            }

            return taskCoordinator.queueImageRequest(
                    commandContext.ctx,
                    "Recent Score",
                    () -> APIHelper.getRecentResponse(n, uidResolution.uid()),
                    replyFactory::rsMessage
            );
        } else if (commandContext.args.length == 1) {
            Integer n = argumentResolver.parsePositiveInt(commandContext.args[0]);
            if (n == null) {
                return RouteDecision.sync(PendingMessage.ofString(Usages.RS_USAGE));
            }
            Long uid = argumentResolver.resolveBoundUid(commandContext.senderUserId);
            if (uid == null) {
                return RouteDecision.sync(PendingMessage.ofString(Usages.NO_BIND_TIP));
            }

            return taskCoordinator.queueImageRequest(
                    commandContext.ctx,
                    "Recent Score",
                    () -> APIHelper.getRecentResponse(n, uid),
                    replyFactory::rsMessage
            );
        } else if (commandContext.args.length == 0) {
            ShortcutTarget target = argumentResolver.parseTarget("rs1", commandContext.senderUserId);
            if (target.isError()) {
                return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
            }

            return taskCoordinator.queueImageRequest(
                    commandContext.ctx,
                    "Score",
                    () -> APIHelper.getScoreResponse(target),
                    replyFactory::scoreMessage
            );
        } else {
            return RouteDecision.sync(PendingMessage.ofString(Usages.RS_USAGE));
        }
    }

    private RouteDecision handleM(CommandContext commandContext) {
        if (commandContext.args.length >= 1) {
            TargetResolution targetResolution = argumentResolver.resolveTargetWithOptionalMention(commandContext.args, commandContext.senderUserId);
            ShortcutTarget target = targetResolution.target();
            if (target.isError()) {
                return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
            }

            if (commandContext.args.length > targetResolution.consumedArgs() + 1) {
                return RouteDecision.sync(PendingMessage.ofString(Usages.M_USAGE));
            }

            String mod = commandContext.args.length == targetResolution.consumedArgs() + 1
                    ? commandContext.args[targetResolution.consumedArgs()]
                    : null;

            return taskCoordinator.queueImageRequest(
                    commandContext.ctx,
                    "Beatmap",
                    () -> APIHelper.getBeatmapResponse(target, mod, getAccessTokenFor(commandContext.senderUserId)),
                    replyFactory::beatmapMessage
            );
        } else {
            return RouteDecision.sync(PendingMessage.ofString(Usages.M_USAGE));
        }
    }

    private RouteDecision handleF(CommandContext commandContext, boolean all) {
        if (!config.binding().requireLogin()) {
            return RouteDecision.sync(PendingMessage.ofString("本指令未启用：需要进行用户登录鉴权。"));
        }

        Long uid = argumentResolver.resolveBoundUid(commandContext.senderUserId);
        if (uid == null) {
            return RouteDecision.sync(PendingMessage.ofString(Usages.NO_BIND_TIP));
        }

        OsuToken token = authHelper.getTokenFor(commandContext.senderUserId);

        if (token == null) {
            return RouteDecision.sync(PendingMessage.ofString("无法获取用户凭据。"));
        }

        return taskCoordinator.queueApiRequest(commandContext.ctx, "Friend List", () -> {
            final Response<UserExtended> self = APIHelper.getSelf(token.accessToken());
            final Response<List<FriendEntry>> response = APIHelper.getFollowed(token.accessToken());
            final List<FriendEntry> content = response.getContent();
            final List<Long> ids = content.stream().map(e -> e.user().getId()).toList();

            final Predicate<Long> filter;
            if (commandContext.inGroup() && !all) {
                final var groupIds = UserDataStore.findBoundUidsByGroup(commandContext.groupId);
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

            return replyFactory.friendMessage(commandContext.ctx, all, self.getContent(), content.size(), mutual, onlyFollowed, onlyFollower);
        });
    }

    private RouteDecision handleFclear(CommandContext commandContext) {
        if (!config.binding().requireLogin()) {
            return RouteDecision.sync(PendingMessage.ofString("本指令未启用：需要进行用户登录鉴权。"));
        }

        Long uid = argumentResolver.resolveBoundUid(commandContext.senderUserId);
        if (uid == null) {
            return RouteDecision.sync(PendingMessage.ofString(Usages.NO_BIND_TIP));
        }

        return RouteDecision.sync(PendingMessage.ofString(
                "已清除 " + UserDataStore.clearFollowed(uid) + " 条好友记录。"
        ));
    }

    private RouteDecision handleDl(CommandContext commandContext) {
        if (commandContext.args.length < 1 || commandContext.args.length > 2) {
            return RouteDecision.sync(PendingMessage.ofString(Usages.DL_USAGE));
        }

        TargetResolution targetResolution = argumentResolver.resolveTargetWithOptionalMention(commandContext.args, commandContext.senderUserId);
        if (commandContext.args.length != targetResolution.consumedArgs()) {
            return RouteDecision.sync(PendingMessage.ofString(Usages.DL_USAGE));
        }

        ShortcutTarget target = targetResolution.target();
        if (target.isError()) {
            return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
        }

        return taskCoordinator.queueApiRequest(
                commandContext.ctx,
                "Download Beatmap",
                () -> replyFactory.dlMessage(
                        commandContext.ctx,
                        APIHelper.getLookupBeatmapsetResponse(target, getAccessTokenFor(commandContext.senderUserId))
                )
        );
    }

    private RouteDecision handleS(CommandContext commandContext) {
        if (commandContext.args.length < 1 || commandContext.args.length > 2) {
            return RouteDecision.sync(PendingMessage.ofString(Usages.S_USAGE));
        }

        TargetResolution targetResolution = argumentResolver.resolveTargetWithOptionalMention(commandContext.args, commandContext.senderUserId);
        if (commandContext.args.length != targetResolution.consumedArgs()) {
            return RouteDecision.sync(PendingMessage.ofString(Usages.S_USAGE));
        }
        ShortcutTarget target = targetResolution.target();
        if (target.isError()) {
            return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
        }

        return taskCoordinator.queueImageRequest(
                commandContext.ctx,
                "Score",
                () -> APIHelper.getScoreResponse(target),
                replyFactory::scoreMessage
        );
    }

    private RouteDecision handleSa(CommandContext commandContext) {
        if (commandContext.args.length < 1 || commandContext.args.length > 2) {
            return RouteDecision.sync(PendingMessage.ofString(Usages.SA_USAGE));
        }

        TargetResolution targetResolution = argumentResolver.resolveTargetWithOptionalMention(commandContext.args, commandContext.senderUserId);
        if (commandContext.args.length != targetResolution.consumedArgs()) {
            return RouteDecision.sync(PendingMessage.ofString(Usages.SA_USAGE));
        }
        ShortcutTarget target = targetResolution.target();
        if (target.isError()) {
            return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
        }

        return taskCoordinator.queueImageRequest(
                commandContext.ctx,
                "Score Analysis",
                () -> APIHelper.getScoreAnalyzeResponse(target),
                replyFactory::scoreAnalyzeMessage
        );
    }

    private RouteDecision handleMa(CommandContext commandContext) {
        if (commandContext.args.length < 1 || commandContext.args.length > 3) {
            return RouteDecision.sync(PendingMessage.ofString(Usages.MA_USAGE));
        }

        TargetResolution targetResolution = argumentResolver.resolveTargetWithOptionalMention(commandContext.args, commandContext.senderUserId);

        ShortcutTarget target = targetResolution.target();
        if (target.isError()) {
            return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
        }

        if (commandContext.args.length == targetResolution.consumedArgs() + 1) {
            Integer index = argumentResolver.parsePositiveInt(commandContext.args[targetResolution.consumedArgs()]);
            if (index == null) {
                return RouteDecision.sync(PendingMessage.ofString(Usages.MA_USAGE));
            }

            return taskCoordinator.queueImageRequest(
                    commandContext.ctx,
                    "Miss Visualize",
                    () -> APIHelper.getMissVisualizeResponse(target, index),
                    null
            );
        } else if (commandContext.args.length == targetResolution.consumedArgs()) {
            return taskCoordinator.queueApiRequest(
                    commandContext.ctx,
                    "Get Score Misses",
                    () -> replyFactory.scoreMissesMessage(commandContext.ctx, APIHelper.getScoreMissesResponse(target))
            );
        } else {
            return RouteDecision.sync(PendingMessage.ofString(Usages.MA_USAGE));
        }
    }

    private RouteDecision handleR(CommandContext commandContext) {
        if (commandContext.args.length < 1 || commandContext.args.length > 3) {
            return RouteDecision.sync(PendingMessage.ofString(Usages.R_USAGE));
        }

        TargetResolution targetResolution = argumentResolver.resolveTargetWithOptionalMention(commandContext.args, commandContext.senderUserId);
        if (commandContext.args.length - targetResolution.consumedArgs() > 1) {
            return RouteDecision.sync(PendingMessage.ofString(Usages.R_USAGE));
        }

        ShortcutTarget target = targetResolution.target();
        if (target.isError()) {
            return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
        }

        TimeDurationParser.TimeRange range = null;

        if (commandContext.args.length == 2) {
            try {
                range = TimeDurationParser.parseRange(commandContext.args[1]);
            } catch (IllegalArgumentException e) {
                return RouteDecision.sync(PendingMessage.ofString("无法解析时间范围"));
            }
        }

        TimeDurationParser.TimeRange finalRange = range;
        return taskCoordinator.queueReplayTask(
                commandContext.ctx,
                "Score Render",
                () -> {
                    APIHelper.ReplayTaskInfo task = APIHelper.createReplayRenderTask(target, finalRange);
                    videoRenderRecord.updateRenderTask(commandContext.senderUserId, task.taskId());
                    return task;
                },
                replyFactory::replayMessage);
    }

    private RouteDecision handleRsc(CommandContext commandContext) {
        if (commandContext.args.length < 1 || commandContext.args.length > 3) {
            return RouteDecision.sync(PendingMessage.ofString(Usages.RSC_USAGE));
        }

        if (commandContext.groupId == null || commandContext.groupId.isBlank()) {
            return RouteDecision.sync(PendingMessage.ofString("/rsc 仅支持群聊使用。"));
        }

        TargetResolution targetResolution = argumentResolver.resolveTargetWithOptionalMention(commandContext.args, commandContext.senderUserId);
        if (commandContext.args.length - targetResolution.consumedArgs() > 2) {
            return RouteDecision.sync(PendingMessage.ofString(Usages.RSC_USAGE));
        }

        ShortcutTarget target = targetResolution.target();
        if (target.isError()) {
            return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
        }

        String extraUidArg = null;
        TimeDurationParser.TimeRange range = null;

        for (int i = targetResolution.consumedArgs(); i < commandContext.args.length; i++) {
            if (commandContext.args[i].startsWith("+")) {
                extraUidArg = commandContext.args[i];
            } else if (TimeDurationParser.isTimeRange(commandContext.args[i])) {
                range = TimeDurationParser.parseRange(commandContext.args[i]);
            } else {
                return RouteDecision.sync(PendingMessage.ofString(Usages.RSC_USAGE));
            }
        }

        UidListResolution uidListResolution = argumentResolver.resolveRscUidList(commandContext.groupId, extraUidArg);
        if (uidListResolution.errorMessage() != null) {
            return RouteDecision.sync(PendingMessage.ofString(uidListResolution.errorMessage()));
        }

        String[] uidArray = uidListResolution.uids();

        TimeDurationParser.TimeRange finalRange = range;

        return taskCoordinator.queueReplayTask(
                commandContext.ctx,
                "Showcase Render",
                () -> {
                    var task = APIHelper.createReplayShowcaseTask(target, uidArray, finalRange, getAccessTokenFor(commandContext.senderUserId));
                    videoRenderRecord.updateRenderTask(commandContext.senderUserId, task.taskId());
                    return task;
                },
                replyFactory::replayMessage);
    }

    private RouteDecision handleMs(CommandContext commandContext) {
        if (commandContext.args.length < 1 || commandContext.args.length > 2) {
            return RouteDecision.sync(PendingMessage.ofString("用法：/ms <谱面集ID 或 快捷查询>"));
        }

        TargetResolution targetResolution = argumentResolver.resolveTargetWithOptionalMention(commandContext.args, commandContext.senderUserId);
        if (commandContext.args.length != targetResolution.consumedArgs()) {
            return RouteDecision.sync(PendingMessage.ofString("用法：/ms <谱面集ID 或 快捷查询>"));
        }
        ShortcutTarget target = targetResolution.target();
        if (target.isError()) {
            return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
        }

        return taskCoordinator.queueImageRequest(
                commandContext.ctx,
                "Beatmapset",
                () -> APIHelper.getBeatmapsetResponse(target, getAccessTokenFor(commandContext.senderUserId)),
                replyFactory::beatmapsetMessage
        );
    }

    private RouteDecision handleSms(CommandContext commandContext) {
        final SearchQuery searchQuery = argumentResolver.resolveSearchQuery(commandContext.query);
        if (searchQuery == null) {
            return RouteDecision.sync(PendingMessage.ofString("用法：/sms [#页数] <搜索关键字>"));
        }
        return taskCoordinator.queueApiRequest(
                commandContext.ctx,
                "Search Beatmapset",
                () -> {
                    Response<List<SearchResultItem>> searchResponse = APIHelper.searchBeatmapSetResponse(searchQuery);
                    return replyFactory.searchMessage(commandContext.ctx, searchResponse, searchQuery);
                });
    }

    private RouteDecision handleLb(CommandContext commandContext) {
        if (commandContext.args.length == 0) {
            if (commandContext.groupId != null && !commandContext.groupId.isBlank()) {
                List<Long> groupBoundUids = UserDataStore.findBoundUidsByGroup(commandContext.groupId);
                if (groupBoundUids.isEmpty()) {
                    return RouteDecision.sync(PendingMessage.ofString("本群还没有已绑定的玩家，请先使用 /bind" + (config.binding().requireLogin() ? "" : " <玩家ID>")));
                }

                return taskCoordinator.queueImageRequest(
                        commandContext.ctx,
                        "Leaderboard",
                        () -> APIHelper.getLeaderboardResponse(groupBoundUids),
                        replyFactory::lbMessage
                );
            }
            Long uid = argumentResolver.resolveBoundUid(commandContext.senderUserId);
            if (uid == null) {
                return RouteDecision.sync(PendingMessage.ofString(Usages.NO_BIND_TIP));
            }

            return taskCoordinator.queueImageRequest(
                    commandContext.ctx,
                    "Leaderboard",
                    () -> APIHelper.getLeaderboardResponse(List.of(uid)),
                    replyFactory::lbMessage
            );
        } else if (commandContext.args.length == 1 || commandContext.args.length == 2) {
            TargetResolution targetResolution = argumentResolver.resolveTargetWithOptionalMention(commandContext.args, commandContext.senderUserId);
            ShortcutTarget target = targetResolution.target();
            if (target.isError()) {
                return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
            }

            int remainingArgs = commandContext.args.length - targetResolution.consumedArgs();
            if (remainingArgs == 0) {
                if (commandContext.groupId != null && !commandContext.groupId.isBlank()) {
                    List<Long> groupBoundUids = UserDataStore.findBoundUidsByGroup(commandContext.groupId);
                    if (groupBoundUids.isEmpty()) {
                        return RouteDecision.sync(PendingMessage.ofString("本群还没有已绑定的玩家，请先使用 /bind" + (config.binding().requireLogin() ? "" : " <玩家ID>")));
                    }
                    return taskCoordinator.queueImageRequest(
                            commandContext.ctx,
                            "Map Leaderboard",
                            () -> APIHelper.getGroupLeaderboardResponse(target, groupBoundUids, getAccessTokenFor(commandContext.senderUserId)),
                            replyFactory::lbMessage
                    );
                }
                Long uid = argumentResolver.resolveBoundUid(commandContext.senderUserId);
                if (uid == null) {
                    return RouteDecision.sync(PendingMessage.ofString(Usages.NO_BIND_TIP));
                }

                return taskCoordinator.queueImageRequest(
                        commandContext.ctx,
                        "Map Leaderboard",
                        () -> APIHelper.getGroupLeaderboardResponse(target, List.of(uid), getAccessTokenFor(commandContext.senderUserId)),
                        replyFactory::lbMessage
                );
            }

            if (remainingArgs != 1) {
                return RouteDecision.sync(PendingMessage.ofString("用法：/lb <谱面ID或快捷查询> [玩家ID列表(逗号分隔)]"));
            }

            String[] uidTokens = commandContext.args[targetResolution.consumedArgs()].split(",");
            if (uidTokens.length == 0) {
                return RouteDecision.sync(PendingMessage.ofString("玩家ID列表不能为空。用法：/lb <谱面ID或快捷查询> [玩家ID列表(逗号分隔)]"));
            }

            List<Long> uids = new LinkedList<>();
            for (String uidToken : uidTokens) {
                Long uid = argumentResolver.parsePositiveLong(uidToken.trim());
                if (uid == null) {
                    return RouteDecision.sync(PendingMessage.ofString("玩家ID列表包含非法值。用法：/lb <谱面ID或快捷查询> [玩家ID列表(逗号分隔)]"));
                }
                uids.add(uid);
            }

            return taskCoordinator.queueImageRequest(
                    commandContext.ctx,
                    "Map Leaderboard",
                    () -> APIHelper.getGroupLeaderboardResponse(target, uids, getAccessTokenFor(commandContext.senderUserId)),
                    replyFactory::lbMessage
            );
        } else {
            return RouteDecision.sync(PendingMessage.ofString("用法：/lb <谱面ID或快捷查询> [玩家ID列表(逗号分隔)]"));
        }
    }

    private RouteDecision handleU(CommandContext commandContext) {
        // TODO: 暂时使用bo8
        if (commandContext.args.length == 1) {
            return route("/bo 8 " + commandContext.args[0], commandContext.senderUserId, commandContext.groupId, commandContext.messageId);
        } else {
            return RouteDecision.sync(PendingMessage.ofString("用法：/u <玩家ID>"));
        }
    }

    private RouteDecision handleRstat(CommandContext commandContext) {
        if (commandContext.args.length != 1 && commandContext.args.length != 0) {
            return RouteDecision.sync(PendingMessage.ofString("用法：/rstat [任务ID]"));
        }

        String jobId;
        if (commandContext.args.length == 0) {
            if (videoRenderRecord.hasRenderTask(commandContext.senderUserId)) {
                jobId = videoRenderRecord.getRenderTask(commandContext.senderUserId);
            } else {
                return RouteDecision.sync(PendingMessage.ofString("未找到渲染请求"));
            }
        } else {
            jobId = commandContext.args[0];
        }

        if (renderResults.containsKey(jobId)) {
            return RouteDecision.sync(PendingMessage.ofVideoUrl(renderResults.get(jobId).videoUrl()), b -> {
                if (b) {
                    renderResults.remove(jobId);
                }
            });
        }

        return RouteDecision.sync(replyFactory.replayStatMessage(commandContext.ctx, jobId, APIHelper.getRenderStat(jobId)));
    }

    private RouteDecision handleInspect(CommandContext commandContext) {
        return RouteDecision.sync(replyFactory.inspectMessage(commandContext.ctx, commandContext.senderUserId,
                isAdmin(commandContext.senderUserId), commandContext.groupId, commandContext.messageId));
    }

    private RouteDecision handleHelp() {
        return RouteDecision.sync(PendingMessage.ofMarkdownRaw("""
                常用指令：
                > /bind - 绑定你的玩家ID
                > /rs - 获取最近的一个成绩
                > /bo [个数] [玩家ID] - 获取一个或多个最佳成绩
                > /rs [个数] [玩家ID] - 获取最近一个或多个成绩
                > /s <成绩ID或快捷查询> - 获取指定成绩
                > /m <谱面ID或快捷查询> - 获取谱面
                > /ms <谱面集ID或快捷查询> - 获取谱面集
                > /r <成绩ID或快捷查询> - 生成成绩回放视频
                > /mp - 获取当前所在的多人房间信息和铺面镜像下载链接
                > /lb <谱面ID> [玩家ID列表] - 获取指定谱面排行榜
                > /f - 获取好友列表
                
                其他指令：
                > /unbind - 解除你的玩家ID绑定
                > /clearhistory - 清除你在群聊中的记录
                > /fall - 获取全部好友列表
                > /fclear - 清除好友记录
                > /sa <成绩ID或快捷查询> - 获取指定成绩分析
                > /ma <成绩ID或快捷查询> [序号] - 获取指定成绩的Miss分析
                > /u <玩家ID> - 获取玩家信息
                > /rsc <谱面ID或快捷查询> [+用户ID列表] - 生成同屏回放视频
                > /rstat [任务ID] - 查询渲染进度
                > /dl <ID或快捷查询> - 获取镜像下载链接
                > /sms [#页数] <关键字> - 搜索谱面集
                > /daily - 获取每日挑战
                > /status - 获取服务器状态
                > /inspect - 获取ID信息
                > /help - 显示此帮助信息
                
                快捷查询参考：
                > - /m rs2 - 获取最近第二个成绩的谱面
                > - /ms bo10 - 获取第十个最好成绩的谱面集
                > - /r 12345 rs1 - 生成ID为12345的玩家的最近一个成绩的回放
                
                > 注：由于官机限制，群聊中需要@机器人才可以接收到指令
                
                详细使用说明请在Github查看
                """));
    }

    private RouteDecision handleStatus() {
        return RouteDecision.sync(PendingMessage.ofString(APIHelper.getServerStatus()));
    }

    private RouteDecision handleUnknown() {
        return RouteDecision.sync(PendingMessage.ofString("未知指令。使用/help获取帮助。"));
    }

    private RouteDecision handleDebugUpload(CommandContext commandContext) {
        if (!config.seira().debugMode()) {
            return RouteDecision.sync(PendingMessage.ofString("未知指令。使用/help获取帮助。"));
        }

        if (!isAdmin(commandContext.senderUserId)) {
            return RouteDecision.sync(PendingMessage.ofString("你没有权限使用此指令。"));
        }

        if (commandContext.args.length != 3) {
            return RouteDecision.sync(PendingMessage.ofString("用法：/debug.upload <type> <cos> <url>"));
        }

        String typeStr = commandContext.args[0];
        String cosStr = commandContext.args[1];
        String urlStr = commandContext.args[2];

        FileInfo fileInfo;
        if (commandContext.groupId != null && !commandContext.groupId.isBlank()) {
            fileInfo = messageSender.uploadGroupMedia(commandContext.groupId, Integer.parseInt(typeStr), urlStr, "true".equals(cosStr));
        } else {
            fileInfo = messageSender.uploadPrivateMedia(commandContext.senderUserId, Integer.parseInt(typeStr), urlStr, "true".equals(cosStr));
        }

        return RouteDecision.sync(PendingMessage.ofString(fileInfo != null
                ? "上传成功，fileId: " + fileInfo
                : "上传失败，请检查日志获取详情"));
    }

    private RouteDecision handleDebugTest(CommandContext commandContext) {
        if (!config.seira().debugMode()) {
            return RouteDecision.sync(PendingMessage.ofString("未知指令。使用/help获取帮助。"));
        }

        if (!isAdmin(commandContext.senderUserId)) {
            return RouteDecision.sync(PendingMessage.ofString("你没有权限使用此指令。"));
        }

        return RouteDecision.sync(replyFactory.testMessage());
    }

    private RouteDecision handleDebugMessage(CommandContext commandContext) {
        if (!config.seira().debugMode()) {
            return RouteDecision.sync(PendingMessage.ofString("未知指令。使用/help获取帮助。"));
        }

        if (!isAdmin(commandContext.senderUserId)) {
            return RouteDecision.sync(PendingMessage.ofString("你没有权限使用此指令。"));
        }

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            new Base64Encoder().decode(commandContext.query, out);
            return RouteDecision.sync(PendingMessage.ofMarkdownRaw(out.toString()));
        } catch (Exception e) {
            return RouteDecision.sync(PendingMessage.ofString("解码失败"));
        }
    }

    private String getAccessTokenFor(String openId) {
        return Optional.ofNullable(authHelper.getTokenFor(openId))
                .map(OsuToken::accessToken)
                .orElse(null);
    }

    private boolean isAdmin(String openId) {
        final List<String> adminIds = config.seira().adminIds();
        if (adminIds == null || adminIds.isEmpty()) {
            return false;
        }
        return adminIds.contains(openId);
    }

    private record CommandContext(Context ctx, String senderUserId, String groupId, String messageId, String[] args,
                                  String query) {
        public boolean inGroup() {
            return groupId != null && !groupId.isBlank();
        }
    }

    private static final class Usages {
        public static final String BO_USAGE = "用法：/bo <个数> [玩家ID/@用户]";
        public static final String NO_BIND_TIP = "你还没有绑定玩家ID，请先使用 /bind 绑定";
        public static final String RS_USAGE = "用法：/rs <个数> [玩家ID/@用户]";
        public static final String M_USAGE = "用法：/m <谱面ID 或 快捷查询> [Mod]";
        public static final String DL_USAGE = "用法：/dl <谱面集ID 或 快捷查询>";
        public static final String S_USAGE = "用法：/s <成绩ID 或 快捷查询>";
        public static final String SA_USAGE = "用法：/sa <成绩ID 或 快捷查询>";
        public static final String MA_USAGE = "用法：/ma <成绩ID 或 快捷查询> [序号]";
        private static final String R_USAGE = "用法：/r <成绩ID 或 快捷查询>";
        private static final String RSC_USAGE = "用法：/rsc <谱面ID或快捷查询> [+用户ID列表，逗号分隔]";
    }
}
